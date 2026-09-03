package gg.mineral.bot.ai.goal

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.effect.PotionEffectType
import gg.mineral.bot.api.entity.living.player.ClientPlayer
import gg.mineral.bot.api.entity.living.player.FakePlayer
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Suspendable
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.math.trajectory.Trajectory
import gg.mineral.bot.api.math.trajectory.Trajectory.CollisionFunction
import gg.mineral.bot.api.math.trajectory.throwable.EnderPearlTrajectory
import gg.mineral.bot.api.screen.type.ContainerScreen
import gg.mineral.bot.api.util.MathUtil
import gg.mineral.bot.api.world.ClientWorld
import gg.mineral.bot.api.world.block.Block
import org.apache.commons.math3.analysis.MultivariateFunction
import org.apache.commons.math3.analysis.UnivariateFunction
import org.apache.commons.math3.optim.InitialGuess
import org.apache.commons.math3.optim.MaxEval
import org.apache.commons.math3.optim.PointValuePair
import org.apache.commons.math3.optim.SimpleBounds
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType
import org.apache.commons.math3.optim.nonlinear.scalar.MultivariateOptimizer
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.BOBYQAOptimizer
import org.apache.commons.math3.optim.univariate.BrentOptimizer
import org.apache.commons.math3.optim.univariate.SearchInterval
import org.apache.commons.math3.optim.univariate.UnivariateObjectiveFunction
import kotlin.math.atan2


class ThrowPearlGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Suspendable, Sporadic, Timebound {
    private companion object {
        const val SPEED_MISSING_ESCAPE_TICKS = 5 * 20
        const val AGGRO_ESCAPE_MAX_DISTANCE = 5.8
        const val AGGRO_ESCAPE_MIN_HEALTH = 4.0f
        const val RETREAT_MIN_LANDING_DISTANCE = 4.0
        const val RETREAT_MAX_LANDING_DISTANCE = 18.0
        const val RETREAT_MIN_EXTRA_ENEMY_DISTANCE = 2.5
    }

    override var executing: Boolean = false
    override var startTime: Long = 0
    override val suspend: Boolean
        get() = clientInstance.currentScreen !is ContainerScreen && !shouldExecute()
    override val maxDuration: Long = 100
    private var lastPearledTick = 0
    private var speedMissingSinceTick = -1
    private val perception = CombatPerception(clientInstance)


    private enum class Type : MathUtil {
        RETREAT {
            override fun test(fakePlayer: FakePlayer, targetState: CombatPerception.PlayerState) =
                targetState.lineOfSightLikelyClear

            /**
             * Checks whether the bot is “at a wall” by sampling a block a short distance
             * in the direction the bot is facing.
             */
            private fun isAtWall(fakePlayer: FakePlayer): Boolean {
                val world = fakePlayer.world

                val posX = fakePlayer.x
                val posY = fakePlayer.y + fakePlayer.eyeHeight
                val posZ = fakePlayer.z
                val yaw = fakePlayer.yaw
                val pitch = 0f

                val checkDistance = 1.0
                val dir = vectorForRotation(pitch, yaw)  // Assumes this helper exists.
                val checkX = posX + dir[0] * checkDistance
                val checkY = posY + dir[1] * checkDistance
                val checkZ = posZ + dir[2] * checkDistance

                val block = world.getBlockAt(checkX, checkY, checkZ)
                return block.id != Block.AIR
            }
        },
        SIDE {
            override fun test(fakePlayer: FakePlayer, targetState: CombatPerception.PlayerState) =
                false /*fakePlayer.distance2DTo(entity.x, entity.z) in 3.6..6.0 && fakePlayer.isOnGround*/
        },
        FORWARD {
            override fun test(fakePlayer: FakePlayer, targetState: CombatPerception.PlayerState) =
                targetState.distance3D > 6.0 && targetState.lineOfSightLikelyClear
        };

        abstract fun test(fakePlayer: FakePlayer, targetState: CombatPerception.PlayerState): Boolean
    }

    override fun shouldExecute(): Boolean {
        updateSpeedOutage()
        if (!canSeeEnemy() || !isPearlCooldownReady()) return false

        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        if (!inventory.contains(Item.ENDER_PEARL)) return false

        val targetState = perception.nearestVisibleEnemy() ?: return false
        return choosePearlType(fakePlayer, targetState) != null
    }

    override fun onStart() {
    }

    private fun canSeeEnemy(): Boolean {
        return perception.canSeeEnemy()
    }

    private fun isPearlCooldownReady(): Boolean {
        return clientInstance.currentTick - lastPearledTick >= 20 * clientInstance.configuration.pearlCooldown
    }

    private fun updateSpeedOutage(): Int {
        val fakePlayer = clientInstance.fakePlayer
        if (fakePlayer.activePotionEffectIds.any { it == PotionEffectType.SPEED.id }) {
            speedMissingSinceTick = -1
            return 0
        }

        if (speedMissingSinceTick == -1) {
            speedMissingSinceTick = clientInstance.currentTick
        }
        return clientInstance.currentTick - speedMissingSinceTick
    }

    private fun isHardAggro(targetState: CombatPerception.PlayerState): Boolean {
        return targetState.lineOfSightLikelyClear &&
            targetState.distance3D <= AGGRO_ESCAPE_MAX_DISTANCE &&
            targetState.lookingAtSelf &&
            (targetState.movingTowardSelf || targetState.distance3D <= 3.6) &&
            (targetState.sprinting || targetState.horizontalSpeed >= 0.12 || targetState.activePotionEffectIds.contains(PotionEffectType.SPEED.id)) &&
            (targetState.holdingWeapon || targetState.heldAttackDamage >= 4.0)
    }

    private fun shouldEmergencyRetreat(fakePlayer: FakePlayer, targetState: CombatPerception.PlayerState): Boolean {
        return updateSpeedOutage() >= SPEED_MISSING_ESCAPE_TICKS &&
            fakePlayer.health > AGGRO_ESCAPE_MIN_HEALTH &&
            isHardAggro(targetState)
    }

    private fun choosePearlType(fakePlayer: FakePlayer, targetState: CombatPerception.PlayerState): Type? {
        if (shouldEmergencyRetreat(fakePlayer, targetState)) {
            return Type.RETREAT
        }

        return Type.entries.firstOrNull {
            it != Type.RETREAT &&
                it.test(fakePlayer, targetState) &&
                fakePlayer.health > clientInstance.configuration.pearlHealthThreshold
        }
    }

    private fun getPearlSlot(): Int {
        var pearlSlot = -1
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            val item = itemStack.item
            if (item.id == Item.ENDER_PEARL) {
                pearlSlot = i
                break
            }
        }

        return pearlSlot
    }

    override fun onTick(tick: Tick) {
        val pearlSlot = getPearlSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        val world = fakePlayer.world

        tick.finishIf("Valid pearl slot not found", pearlSlot == -1)

        val targetState = perception.bestTarget()

        tick.finishIf("Enemy is not present", targetState == null)
        targetState ?: return

        val type = choosePearlType(fakePlayer, targetState) ?: run {
            tick.finishIf("No valid type found", true)
            return
        }

        if (!isPearlCooldownReady()) {
            tick.finishIf("Pearl cooldown active", true)
            return
        }

        if (type == Type.RETREAT) {
            val retreatYaw = perception.safeYawAwayFromNearestEnemy()
            val terrain = perception.terrainAhead(retreatYaw, 1.0)
            if (terrain.frontBlocked || terrain.lavaAhead || terrain.dropAhead) {
                tick.finishIf("No safe retreat angle", true)
                return
            }
        }

        val typeStillValid = when (type) {
            Type.RETREAT -> shouldEmergencyRetreat(fakePlayer, targetState)
            else -> type.test(fakePlayer, targetState)
        }
        if (!typeStillValid) {
            tick.finishIf("Pearl type no longer valid", true)
            return
        }

        tick.prerequisite("In Hotbar", isItemReadyInHotbar(pearlSlot, fakePlayer.inventory)) {
            moveItemToHotbar(pearlSlot, fakePlayer.inventory)
        }

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(
                10,
                Key.Type.KEY_ESCAPE
            )
        }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(pearlSlot)) {
            selectHotbarSlot(resolveHotbarSlot(pearlSlot))
        }

        tick.finishIf("Not Holding Valid Pearl", inventory.heldItemStack?.item?.id != Item.ENDER_PEARL)

        val targetX = targetState.x + targetState.velocityX
        val targetY = targetState.y + targetState.velocityY
        val targetZ = targetState.z + targetState.velocityZ

        val collisionFunction = when (type) {
            Type.FORWARD -> CollisionFunction { x1: Double, y1: Double, z1: Double ->
                abs(
                    x1
                            - targetX
                ) < 4 && abs(
                    y1
                            - targetY
                ) < 1 && abs(
                    z1
                            - targetZ
                ) < 4 && hasHitBlock(world, x1, y1, z1)
            }

            Type.RETREAT -> CollisionFunction { x1: Double, y1: Double, z1: Double ->
                hasHitBlock(world, x1, y1, z1) &&
                    fakePlayer.distance2DTo(x1, z1) in RETREAT_MIN_LANDING_DISTANCE..RETREAT_MAX_LANDING_DISTANCE &&
                    sqrt((x1 - targetState.x) * (x1 - targetState.x) + (z1 - targetState.z) * (z1 - targetState.z)) >=
                    targetState.distance2D + RETREAT_MIN_EXTRA_ENEMY_DISTANCE
            }

            Type.SIDE -> CollisionFunction { x1: Double, y1: Double, z1: Double ->
                val dX = abs(x1 - targetX)
                val dZ = abs(z1 - targetZ)

                sqrt(dX * dX + dZ * dZ) in 2.5..3.6 && hasHitBlock(
                    world,
                    x1,
                    y1,
                    z1
                )
            }
        }

        // TODO: Implement this
        /*tick.executeAsync(0, {
            when (type) {
                Type.FORWARD -> {
                    val x = targetX - fakePlayer.x
                    val z = targetZ - fakePlayer.z
                    val yaw = if (z < 0.0 && x < 0.0) (90.0 + toDegrees(fastArcTan(z / x))).toFloat()
                    else if (z < 0.0 && x > 0.0) (-90.0 + toDegrees(fastArcTan(z / x))).toFloat()
                    else toDegrees(-fastArcTan(x / z)).toFloat()

                    minimizePitch(fakePlayer, yaw, collisionFunction) {
                        it.airTimeTicks.toDouble()
                    }
                }

                Type.RETREAT -> {
                    optimizeAngles(GoalType.MAXIMIZE, fakePlayer, collisionFunction) {
                        it.distance3DToSq(
                            targetX,
                            targetY,
                            targetZ
                        )
                    }
                }

                Type.SIDE -> {
                    optimizeAngles(GoalType.MINIMIZE, fakePlayer, collisionFunction) {
                        it.airTimeTicks.toDouble()
                    }
                }
            }
        }) {
            setMouseYaw(it[0])
            setMousePitch(it[1])
        }*/

        val angles = when (type) {
            Type.RETREAT -> getRetreatAngles()
            else -> getAnglesToFeet(fakePlayer, targetState)
        }
        setMouseYaw(angles[0])
        setMousePitch(angles[1])

        val trajectory = EnderPearlTrajectory(
            world,
            fakePlayer.x,
            fakePlayer.y + fakePlayer.eyeHeight,
            fakePlayer.z,
            fakePlayer.yaw,
            fakePlayer.pitch,
            collisionFunction
        )

        tick.executeAsync(1, {
            trajectory.compute(100) == Trajectory.Result.VALID
        }) {
            if (!it) return@executeAsync
            lastPearledTick = clientInstance.currentTick
            pressButton(10, MouseButton.Type.RIGHT_CLICK)
            tick.finishIf("Thrown Pearl", it)
        }
    }

    override fun onEnd() {
    }

    private fun getAnglesToFeet(player: ClientPlayer, targetState: CombatPerception.PlayerState): FloatArray {
        val leadScale = (targetState.distance2D / 8.0).coerceIn(0.25, 1.2)

        val predictedX = targetState.x + targetState.velocityX * leadScale
        val predictedZ = targetState.z + targetState.velocityZ * leadScale

        val x = predictedX - player.x
        val z = predictedZ - player.z

        // Intentionally bias the target to the feet area to avoid over-shooting behind moving targets.
        val feetY = targetState.y + 0.05
        val y = feetY - (player.y + player.eyeHeight)

        val yaw = Math.toDegrees(atan2(z, x)).toFloat() - 90.0f
        val d1 = sqrt(x * x + z * z)
        val basePitch = Math.toDegrees(atan2(y, d1)).toFloat()

        // Add extra downward bias so pearls are thrown at/under feet rather than face.
        val pitch = (basePitch + 12.0f).coerceIn(-89f, 89f)

        return floatArrayOf(yaw, pitch)
    }

    private fun getRetreatAngles(): FloatArray {
        return floatArrayOf(perception.safeYawAwayFromNearestEnemy(), -4.0f)
    }

    private fun minimizePitch(
        fakePlayer: FakePlayer,
        yaw: Float,
        collisionFunction: CollisionFunction,
        valueFunction: (EnderPearlTrajectory) -> Double
    ): FloatArray {
        val objective = UnivariateFunction { pitch ->
            val simulator = EnderPearlTrajectory(
                fakePlayer.world,
                fakePlayer.x,
                fakePlayer.y + fakePlayer.eyeHeight,
                fakePlayer.z,
                yaw,
                pitch.toFloat(),
                collisionFunction
            )
            if (simulator.compute(100) === Trajectory.Result.VALID) valueFunction.invoke(simulator) else Double.MAX_VALUE
        }

        val optimizer = BrentOptimizer(1e-10, 1e-14)

        val result = optimizer.optimize(
            MaxEval(180),
            UnivariateObjectiveFunction(objective),
            GoalType.MINIMIZE,
            SearchInterval(-60.0, 60.0)
        )

        return floatArrayOf(yaw, result.point.toFloat())
    }

    private fun optimizeAngles(
        goalType: GoalType,
        fakePlayer: FakePlayer,
        collisionFunction: CollisionFunction,
        valueFunction: (EnderPearlTrajectory) -> Double
    ): FloatArray {
        val objective = MultivariateFunction { angles ->
            val simulator = EnderPearlTrajectory(
                fakePlayer.world,
                fakePlayer.x,
                fakePlayer.y + fakePlayer.eyeHeight,
                fakePlayer.z,
                angles[0].toFloat(),
                angles[1].toFloat(),
                collisionFunction
            )
            if (simulator.compute(100) === Trajectory.Result.VALID) valueFunction.invoke(simulator) else if (goalType == GoalType.MINIMIZE) Double.MAX_VALUE else Double.MIN_VALUE
        }

        val optimizer: MultivariateOptimizer = BOBYQAOptimizer(4)

        val lowerBounds = doubleArrayOf(-180.0, -90.0)
        val upperBounds = doubleArrayOf(180.0, 90.0)

        fun wrapAngleTo180_float(par0: Float): Float {
            var par0 = par0
            par0 %= 360.0f

            if (par0 >= 180.0f) {
                par0 -= 360.0f
            }

            if (par0 < -180.0f) {
                par0 += 360.0f
            }

            return par0
        }

        val initialGuess = doubleArrayOf(
            wrapAngleTo180_float(fakePlayer.yaw).toDouble(),
            wrapAngleTo180_float(fakePlayer.pitch).toDouble()
        )

        val optimum: PointValuePair = optimizer.optimize(
            MaxEval(180),
            ObjectiveFunction(objective),
            goalType,
            InitialGuess(initialGuess),
            SimpleBounds(lowerBounds, upperBounds)
        )

        return floatArrayOf(optimum.point[0].toFloat(), optimum.point[1].toFloat())
    }

    private fun hasHitBlock(world: ClientWorld?, x: Double, y: Double, z: Double): Boolean {
        val xTile = floor(x)
        val yTile = floor(y)
        val zTile = floor(z)
        val block = world?.getBlockAt(xTile, yTile, zTile)

        if (world != null && block != null && block.id != Block.AIR) {
            val collisionBoundingBox = block.getCollisionBoundingBox(
                world,
                xTile,
                yTile, zTile
            )

            return collisionBoundingBox?.isVecInside(x, y, z) == true
        }

        return false
    }

    override fun onEvent(event: Event): Boolean {
        return false
    }

    override fun onGameLoop() {
    }
}
