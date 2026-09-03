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
import gg.mineral.bot.api.math.simulation.PlayerMotionSimulator
import gg.mineral.bot.api.math.trajectory.Trajectory
import gg.mineral.bot.api.math.trajectory.throwable.SplashPotionTrajectory
import gg.mineral.bot.api.screen.type.ContainerScreen
import gg.mineral.bot.api.world.ClientWorld
import gg.mineral.bot.api.world.block.Block
import org.apache.commons.math3.analysis.UnivariateFunction
import org.apache.commons.math3.optim.MaxEval
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType
import org.apache.commons.math3.optim.univariate.BrentOptimizer
import org.apache.commons.math3.optim.univariate.SearchInterval
import org.apache.commons.math3.optim.univariate.UnivariateObjectiveFunction

class ThrowDebuffPotGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound,
    Suspendable {
    override val maxDuration: Long = 100
    override var startTime: Long = 0
    override var executing: Boolean = false
    private var lastPotTick = 0
    private var effects: Set<Int> = emptySet()
    private val perception = CombatPerception(clientInstance)
    override val suspend: Boolean
        get() = clientInstance.currentScreen !is ContainerScreen && !shouldExecute()

    override fun shouldExecute(): Boolean {
        if (clientInstance.currentTick - lastPotTick < 20) return false

        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        val closestEnemy = closestEnemyState() ?: return false

        val debuffEffects = setOf(
            PotionEffectType.HARM.id,
            PotionEffectType.POISON.id,
            PotionEffectType.WEAKNESS.id,
            PotionEffectType.SLOW.id,
            PotionEffectType.BLINDNESS.id,
            PotionEffectType.HUNGER.id,
            PotionEffectType.SLOW_DIGGING.id
        )

        val effects = debuffEffects.subtract(closestEnemy.activePotionEffectIds)
        if (effects.isEmpty()) return false

        return (inventory.containsPotion {
            it.effects.any { effect ->
                effects.contains(effect.potionID)
            }
        } && closestEnemy.lineOfSightLikelyClear && closestEnemy.distance3D * closestEnemy.distance3D in 25.0..64.0).apply {
            if (this) this@ThrowDebuffPotGoal.effects = effects
        }
    }

    override fun onStart() {
        pressKey(Key.Type.KEY_S)
        unpressKey(Key.Type.KEY_W, Key.Type.KEY_A, Key.Type.KEY_D)
    }

    private fun angleTowardsEnemies(): Float {
        return perception.yawTowardsNearestEnemy()
    }

    private fun closestEnemyState(): CombatPerception.PlayerState? {
        return perception.bestTarget()
    }

    private fun closestEnemy(): ClientPlayer? {
        return closestEnemyState()?.entity
    }

    // Extension functions for adjusting the bot’s aim.
    private fun PlayerMotionSimulator.setMouseYaw(yaw: Float) {
        val rotYaw = this.yaw
        mouse.changeYaw(angleDifference(rotYaw, yaw))
    }

    private fun getDebuffSlot(): Int {
        var debuffSlot = -1
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            val item = itemStack.item
            if (item.id == Item.POTION && itemStack.potion?.effects?.any {
                    effects.contains(it.potionID)
                } == true) {
                debuffSlot = i
                break
            }
        }

        return debuffSlot
    }

    override fun onTick(tick: Tick) {
        val debuffSlot = getDebuffSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        tick.finishIf("No Valid Debuff Potion Found", debuffSlot == -1)

        tick.prerequisite("In Hotbar", isItemReadyInHotbar(debuffSlot, inventory)) {
            moveItemToHotbar(debuffSlot, inventory)
        }

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(
                10,
                Key.Type.KEY_ESCAPE
            )
        }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(debuffSlot)) {
            selectHotbarSlot(resolveHotbarSlot(debuffSlot))
        }

        tick.finishIf("Not Holding Valid Potion", inventory.heldItemStack?.let { itemStack ->
            itemStack.item.id == Item.POTION && itemStack.potion?.effects?.any {
                effects.contains(it.potionID)
            } == true
        } == false)

        tick.execute {
            setMouseYaw(angleTowardsEnemies())
        }

        val closestEnemy = closestEnemy() ?: return

        tick.executeAsync(0, {
            minimizePitch(fakePlayer, closestEnemy) { it.airTimeTicks.toDouble() }
        }) {
            setMousePitch(it)
        }

        val simulator = fakePlayer.motionSimulator().apply {
            setMouseYaw(fakePlayer.yaw)
            keyboard.pressKey(Key.Type.KEY_S)
            keyboard.unpressKey(Key.Type.KEY_W, Key.Type.KEY_A, Key.Type.KEY_D)
        }

        val enemySimulator = closestEnemy.motionSimulator()
        val trajectory = object : SplashPotionTrajectory(
            fakePlayer.world,
            fakePlayer.x,
            fakePlayer.y + fakePlayer.eyeHeight,
            fakePlayer.z,
            fakePlayer.yaw,
            fakePlayer.pitch, { x, y, z ->
                hasHitBlock(fakePlayer.world, x, y, z) &&
                        enemySimulator.distance3DToSq(x, y, z).let {
                            if (it < 16.0) 1.0 - sqrt(it) / 4.0 > 0.5
                            else false
                        }
                        && simulator.distance3DToSq(x, y, z)
                    .let { if (it < 16.0) 1.0 - sqrt(it) / 4.0 <= 0.01 else true }
            }
        ) {
            override fun tick(): Trajectory.Result {
                enemySimulator.execute(50)
                simulator.execute(50)
                return super.tick()
            }
        }

        tick.executeAsync(1, {
            trajectory.compute(100) == Trajectory.Result.VALID
        }) {
            if (!it) return@executeAsync
            lastPotTick = clientInstance.currentTick
            pressButton(10, MouseButton.Type.RIGHT_CLICK)
            tick.finishIf("Thrown Debuff", it)
        }
    }

    override fun onEnd() {
        unpressKey(Key.Type.KEY_S)
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
    }

    private fun minimizePitch(
        fakePlayer: FakePlayer,
        closestEnemy: ClientPlayer?,
        valueFunction: (SplashPotionTrajectory) -> Double
    ): Float {
        closestEnemy ?: return fakePlayer.pitch
        val objective = UnivariateFunction { pitch ->
            val simulator = fakePlayer.motionSimulator().apply {
                setMouseYaw(fakePlayer.yaw)
                keyboard.pressKey(Key.Type.KEY_S)
                keyboard.unpressKey(Key.Type.KEY_W, Key.Type.KEY_A, Key.Type.KEY_D)
            }

            val enemySimulator = closestEnemy.motionSimulator()
            val trajectory = object : SplashPotionTrajectory(
                fakePlayer.world,
                fakePlayer.x,
                fakePlayer.y + fakePlayer.eyeHeight,
                fakePlayer.z,
                fakePlayer.yaw,
                pitch.toFloat(), { x, y, z ->
                    hasHitBlock(fakePlayer.world, x, y, z) && enemySimulator.distance3DToSq(x, y, z).let {
                        if (it < 16.0) 1.0 - sqrt(it) / 4.0 > 0.5
                        else false
                    }
                            && simulator.distance3DToSq(x, y, z)
                        .let { if (it < 16.0) 1.0 - sqrt(it) / 4.0 <= 0.01 else true }
                }
            ) {
                override fun tick(): Trajectory.Result {
                    enemySimulator.execute(50)
                    simulator.execute(50)
                    return super.tick()
                }
            }
            if (trajectory.compute(100) === Trajectory.Result.VALID)
                valueFunction.invoke(trajectory)
            else
                Double.MAX_VALUE
        }

        val optimizer = BrentOptimizer(1e-10, 1e-14)

        val result = optimizer.optimize(
            MaxEval(180),
            UnivariateObjectiveFunction(objective),
            GoalType.MINIMIZE,
            SearchInterval(-90.0, 90.0)
        )

        return result.value.toFloat()
    }

    private fun hasHitBlock(world: ClientWorld?, x: Double, y: Double, z: Double): Boolean {
        val xTile = floor(x)
        val yTile = floor(y)
        val zTile = floor(z)
        val block = world?.getBlockAt(xTile, yTile, zTile)

        if (world != null && block != null && block.id != Block.AIR) {
            val collisionBoundingBox = block.getCollisionBoundingBox(world, xTile, yTile, zTile)
            return collisionBoundingBox != null && collisionBoundingBox.isVecInside(x, y, z)
        }

        return false
    }

    override fun onEvent(event: Event): Boolean {
        return false
    }

    public override fun onGameLoop() {
        // No changes made in the game loop.
    }
}
