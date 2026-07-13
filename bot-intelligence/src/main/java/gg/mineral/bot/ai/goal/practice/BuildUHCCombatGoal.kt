package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.effect.PotionEffectType
import gg.mineral.bot.api.entity.living.player.ClientPlayer
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.GoalDebugState
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.world.block.Block

class BuildUHCCombatGoal(clientInstance: ClientInstance) :
        InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    private companion object {
        const val GAPPLE_EAT_THRESHOLD = 12.0f
        const val LAVA_PLACE_COOLDOWN_TICKS = 80
        const val FLUID_RECOVERY_RANGE = 3.0
        const val FLUID_RECOVERY_MIN_ENEMY_DISTANCE = 5.0
        const val MAX_BUCKET_USE_DISTANCE = 5.15
        const val BUCKET_AIM_TOLERANCE = 7f
        const val EMERGENCY_WATER_PITCH = 88f
        const val EMERGENCY_WATER_PITCH_TOLERANCE = 5f
    }

    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 72

    private var lastLavaPlaceTick = 0
    private var actionLockUntilTick = 0
    private var matchStartTick = -1
    private var preFightGappleUsed = false
    private var waterState = WaterState.IDLE
    private var waterStateStartTick = 0
    private var waterAimStartTick = -1
    private var lastGappleEatTick = -200
    private var lastHeadEatTick = -200
    private var placedLavaTick = -200
    private var placedWaterTick = -200
    private var placedLavaX = Double.NaN
    private var placedLavaY = Double.NaN
    private var placedLavaZ = Double.NaN
    private var pendingLavaTarget: BlockUseTarget? = null
    private var lavaAimStartTick = -1
    private var pendingRecoveryTarget: FluidTarget? = null
    private var recoveryAimStartTick = -1
    private var eatState = EatState.NONE
    private var eatStartTick = -1
    private val perception = CombatPerception(clientInstance)

    private enum class WaterState {
        IDLE,
        AIMING_TO_PLACE,
        WAIT_TO_PICKUP,
        AIMING_TO_PICKUP
    }

    private enum class EatState {
        NONE,
        OPENER_GAPPLE,
        GOLDEN_APPLE,
        GOLDEN_HEAD
    }

    override fun shouldExecute(): Boolean {
        if (matchStartTick == -1) {
            matchStartTick = clientInstance.currentTick
        }

        if (isEatingApple()) return true

        val fakePlayer = clientInstance.fakePlayer
        val enemyState = getClosestEnemyState()

        if (needsEmergencyWater()) return true

        // BuildUHC opening: around 4s after spawn, pre-gap once when enemy is not already close.
        if (!preFightGappleUsed &&
                clientInstance.currentTick - matchStartTick >= 80 &&
                hasNormalGapple() &&
                (enemyState == null || enemyState.distance3D > 10.0)
        ) {
            return true
        }

        enemyState ?: return false
        val distance = enemyState.distance3D

        if (needsGoldenHead() && canEatHeadNow(fakePlayer.health)) return true
        if (needsGoldenApple() && fakePlayer.health < GAPPLE_EAT_THRESHOLD && canEatGappleNow(fakePlayer.health)) return true
        if (shouldRecoverPlacedFluid(enemyState)) return true

        return canStartAction() &&
                hasLava() &&
                distance in 1.9..5.3 &&
                fakePlayer.isOnGround &&
                enemyState.lineOfSightLikelyClear &&
                findLavaPlacementTarget(enemyState) != null
    }

    override fun onStart() {
        actionLockUntilTick = 0
        waterState = WaterState.IDLE
        waterAimStartTick = -1
        pendingLavaTarget = null
        lavaAimStartTick = -1
        pendingRecoveryTarget = null
        recoveryAimStartTick = -1
    }

    private fun isEatingApple(): Boolean = eatState != EatState.NONE

    private fun hasRegenEffect(): Boolean {
        val regenId = PotionEffectType.REGENERATION.id
        return clientInstance.fakePlayer.activePotionEffectIds.any { it == regenId }
    }

    private fun beginEating(state: EatState) {
        eatState = state
        eatStartTick = clientInstance.currentTick
        lockAction(34)
    }

    private fun clearEatingState() {
        eatState = EatState.NONE
        eatStartTick = -1
        unpressButton(MouseButton.Type.RIGHT_CLICK)
    }

    private fun canStartAction(): Boolean {
        return clientInstance.currentTick >= actionLockUntilTick
    }

    private fun lockAction(ticks: Int) {
        actionLockUntilTick = clientInstance.currentTick + ticks
    }

    private fun keepForward() {
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_A, Key.Type.KEY_D, Key.Type.KEY_S)
    }

    private fun isAimAligned(
        target: AimAngles,
        yawTolerance: Float = BUCKET_AIM_TOLERANCE,
        pitchTolerance: Float = BUCKET_AIM_TOLERANCE
    ): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        return kotlin.math.abs(angleDifference(fakePlayer.yaw, target.yaw)) <= yawTolerance &&
                kotlin.math.abs(angleDifference(fakePlayer.pitch, target.pitch)) <= pitchTolerance
    }

    private fun aimAt(target: AimAngles) {
        setMouseYaw(target.yaw)
        setMousePitch(target.pitch)
    }

    private fun hasLava(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        return inventory.contains(Item.LAVA_BUCKET)
    }

    private fun hasWaterBucket(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        return inventory.contains(Item.WATER_BUCKET)
    }

    private fun hasEmptyBucket(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        return inventory.contains(Item.BUCKET)
    }

    private fun hasNormalGapple(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.GOLDEN_APPLE && item.durability == 0) return true
        }
        return false
    }

    private fun needsGoldenApple(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        return fakePlayer.health < 14 && inventory.contains(Item.GOLDEN_APPLE)
    }

    private fun needsGoldenHead(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        if (fakePlayer.health > 6) return false

        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.GOLDEN_APPLE && item.durability == 1) return true
        }
        return false
    }

    private fun getClosestEnemyState(): CombatPerception.PlayerState? {
        return perception.bestTarget()
    }

    private fun getLavaSlot(): Int {
        val inventory = clientInstance.fakePlayer.inventory
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.LAVA_BUCKET) return i
        }
        return -1
    }

    private fun getGoldenAppleSlot(preferHead: Boolean = false): Int {
        val inventory = clientInstance.fakePlayer.inventory
        var normalGapple = -1

        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.GOLDEN_APPLE) {
                if (preferHead && item.durability == 1) return i
                if (!preferHead && item.durability == 0 && normalGapple == -1) normalGapple = i
                if (normalGapple == -1) normalGapple = i
            }
        }
        return normalGapple
    }

    private fun getWaterPlacementSlot(): Int {
        val inventory = clientInstance.fakePlayer.inventory
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.WATER_BUCKET) return i
        }
        return -1
    }

    private fun getBucketRecoverySlot(): Int {
        val inventory = clientInstance.fakePlayer.inventory
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.BUCKET) return i
        }
        return -1
    }

    private fun shouldPlaceLava(enemy: CombatPerception.PlayerState): Boolean {
        if (clientInstance.currentTick - lastLavaPlaceTick < LAVA_PLACE_COOLDOWN_TICKS) return false
        if (!hasLava()) return false

        val fakePlayer = clientInstance.fakePlayer
        val distance = enemy.distance3D

        return distance >= 1.9 &&
                distance <= 5.3 &&
                fakePlayer.isOnGround &&
                enemy.lineOfSightLikelyClear &&
                findLavaPlacementTarget(enemy) != null
    }

    private fun allBucketsEmpty(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        var bucketCount = 0
        var filledBuckets = 0
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            when (item.item.id) {
                Item.BUCKET -> bucketCount++
                Item.WATER_BUCKET, Item.LAVA_BUCKET -> {
                    bucketCount++
                    filledBuckets++
                }
            }
        }
        return bucketCount >= 4 && filledBuckets == 0
    }

    private fun isInDangerousBlock(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world

        val samples =
                arrayOf(
                        doubleArrayOf(0.0, 0.0),
                        doubleArrayOf(0.28, 0.0),
                        doubleArrayOf(-0.28, 0.0),
                        doubleArrayOf(0.0, 0.28),
                        doubleArrayOf(0.0, -0.28)
                )

        fun isHazard(id: Int): Boolean {
            return id == Block.LAVA_FLOWING || id == Block.LAVA_STILL || id == Block.FIRE
        }

        for (offset in samples) {
            val x = fakePlayer.x + offset[0]
            val z = fakePlayer.z + offset[1]

            val below = world.getBlockAt(x, fakePlayer.y - 1.0, z).id
            val feet = world.getBlockAt(x, fakePlayer.y, z).id
            val body = world.getBlockAt(x, fakePlayer.y + 0.7, z).id
            val head = world.getBlockAt(x, fakePlayer.y + 1.2, z).id

            if (isHazard(below) || isHazard(feet) || isHazard(body) || isHazard(head)) {
                return true
            }
        }

        return false
    }

    private fun fakePlayerIsBurning(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        return runCatching {
            val method = fakePlayer.javaClass.methods.firstOrNull {
                it.name == "isBurning" && it.parameterCount == 0
            } ?: return@runCatching false
            (method.invoke(fakePlayer) as? Boolean) == true
        }.getOrDefault(false)
    }

    private fun needsEmergencyWater(): Boolean {
        if (waterState != WaterState.IDLE) return true
        if (allBucketsEmpty()) return false
        return hasWaterBucket() && (isInDangerousBlock() || fakePlayerIsBurning())
    }

    private fun canEatGappleNow(health: Float): Boolean {
        if (health <= 3.0f) return true
        return clientInstance.currentTick - lastGappleEatTick >= 100
    }

    private fun canEatHeadNow(health: Float): Boolean {
        if (health <= 2.5f) return true
        return clientInstance.currentTick - lastHeadEatTick >= 200
    }

    private fun handleOngoingEat(
        tick: Tick,
        inventory: gg.mineral.bot.api.inv.Inventory,
        enemy: CombatPerception.PlayerState?
    ): Boolean {
        if (!isEatingApple()) return false

        val preferHead = eatState == EatState.GOLDEN_HEAD
        val eatSlot = getGoldenAppleSlot(preferHead = preferHead)
        if (eatSlot == -1) {
            tick.execute {
                clearEatingState()
                finish()
            }
            return true
        }

        tick.prerequisite("Eat Item In Hotbar", eatSlot <= 8) {
            moveItemToHotbar(eatSlot, inventory)
        }
        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }
        tick.prerequisite("Holding Eat Item", inventory.heldSlot == resolveHotbarSlot(eatSlot)) {
            selectHotbarSlot(resolveHotbarSlot(eatSlot))
        }

        tick.execute {
            pressButton(MouseButton.Type.RIGHT_CLICK)
            keepForward()
            if (enemy != null && enemy.distance3D < 12.0) {
                setMouseYaw(perception.safeYawAwayFromNearestEnemy())
            }
        }

        val eatenLongEnough = eatStartTick != -1 && clientInstance.currentTick - eatStartTick >= 34
        if (eatenLongEnough || hasRegenEffect()) {
            tick.execute { clearEatingState() }
        }

        return true
    }

    private fun isSafeToEat(enemy: CombatPerception.PlayerState?): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        if (enemy == null) return true
        if (fakePlayer.health <= 2.5f) return true
        return enemy.distance3D >= 4.4 && (!enemy.pressuringSelf || enemy.distance3D >= 6.2)
    }

    private fun getRodSlot(): Int {
        val inventory = clientInstance.fakePlayer.inventory
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.FISHING_ROD) return i
        }
        return -1
    }

    private fun tryCreateEatWindow(
        tick: Tick,
        enemy: CombatPerception.PlayerState,
        inventory: gg.mineral.bot.api.inv.Inventory
    ): Boolean {
        val rodSlot = getRodSlot()
        if (rodSlot == -1) return false

        tick.prerequisite("Rod In Hotbar", rodSlot <= 8) { moveItemToHotbar(rodSlot, inventory) }
        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }
        tick.prerequisite("Holding Rod", inventory.heldSlot == resolveHotbarSlot(rodSlot)) {
            selectHotbarSlot(resolveHotbarSlot(rodSlot))
        }

        tick.execute {
            val bot = clientInstance.fakePlayer
            val leadTicks = if (enemy.movingTowardSelf) 1.6 else 2.4
            val predictedX = enemy.x + enemy.velocityX * leadTicks
            val predictedY = enemy.y + enemy.entity.eyeHeight * 0.57
            val predictedZ = enemy.z + enemy.velocityZ * leadTicks

            val predictedEnemy = object : ClientPlayer by enemy.entity {
                override val x: Double get() = predictedX
                override val y: Double get() = predictedY
                override val z: Double get() = predictedZ
            }
            val angles = computeOptimalYawAndPitch(bot, predictedEnemy)
            setMouseYaw(angles[1])
            setMousePitch((angles[0] + 4f).coerceIn(-14f, 38f))
            pressButton(35, MouseButton.Type.RIGHT_CLICK)
            lockAction(5)
        }
        return true
    }

    private data class AimAngles(val yaw: Float, val pitch: Float)

    private data class FluidTarget(val x: Double, val y: Double, val z: Double, val score: Double)

    private data class BlockUseTarget(
        val blockX: Int,
        val blockY: Int,
        val blockZ: Int,
        val aimX: Double,
        val aimY: Double,
        val aimZ: Double,
        val score: Double
    )

    private fun isFluidBlock(id: Int): Boolean {
        return id == Block.WATER_FLOWING ||
                id == Block.WATER_STILL ||
                id == Block.LAVA_FLOWING ||
                id == Block.LAVA_STILL
    }

    private fun isReplaceableForFluid(id: Int): Boolean {
        return id == Block.AIR ||
                id == Block.FIRE ||
                id == Block.TALL_GRASS ||
                id == Block.DEAD_BUSH ||
                id == Block.DANDELION ||
                id == Block.POPPY ||
                id == Block.BROWN_MUSHROOM ||
                id == Block.RED_MUSHROOM ||
                id == Block.SNOW_LAYER ||
                id == Block.CARPET
    }

    private fun interactionEyeY(): Double {
        val fakePlayer = clientInstance.fakePlayer
        return fakePlayer.headY + fakePlayer.eyeHeight
    }

    private fun anglesToPoint(x: Double, y: Double, z: Double): AimAngles {
        val fakePlayer = clientInstance.fakePlayer
        val dx = x - fakePlayer.x
        val dy = y - interactionEyeY()
        val dz = z - fakePlayer.z
        val horizontalDistance = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001)
        val yaw = toDegrees(fastArcTan2(-dx, dz)).toFloat()
        val pitch = toDegrees(-fastArcTan2(dy, horizontalDistance)).toFloat()
        return AimAngles(yaw, pitch)
    }

    private fun pitchToRecoveryTarget(target: FluidTarget): Float {
        val fakePlayer = clientInstance.fakePlayer
        val dx = target.x - fakePlayer.x
        val dy = target.y - interactionEyeY()
        val dz = target.z - fakePlayer.z
        val horizontalDistance = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001)
        return toDegrees(-fastArcTan2(dy, horizontalDistance)).toFloat().coerceIn(-20f, 85f)
    }

    private fun hasClearUseLineTo(x: Double, y: Double, z: Double): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world
        val startX = fakePlayer.x
        val startY = interactionEyeY()
        val startZ = fakePlayer.z
        val dx = x - startX
        val dy = y - startY
        val dz = z - startZ
        val distance = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.001)
        val steps = (distance * 4.0).toInt().coerceIn(3, 24)

        for (step in 1 until steps) {
            val t = step.toDouble() / steps.toDouble()
            val id = world.getBlockAt(startX + dx * t, startY + dy * t, startZ + dz * t).id
            if (!isReplaceableForFluid(id) && !isFluidBlock(id)) {
                return false
            }
        }

        return true
    }

    private fun hasSolidSupport(x: Int, y: Int, z: Int): Boolean {
        val world = clientInstance.fakePlayer.world
        val block = world.getBlockAt(x, y, z)
        return block.getCollisionBoundingBox(world, x, y, z) != null
    }

    private fun findPlaceableFluidY(x: Int, z: Int, aroundY: Int): Int? {
        val world = clientInstance.fakePlayer.world
        for (y in (aroundY + 1) downTo (aroundY - 2)) {
            val targetId = world.getBlockAt(x, y, z).id
            val belowId = world.getBlockAt(x, y - 1, z).id
            if (isReplaceableForFluid(targetId) &&
                    !isFluidBlock(belowId) &&
                    hasSolidSupport(x, y - 1, z)
            ) {
                return y
            }
        }
        return null
    }

    private fun findLavaPlacementTarget(enemy: CombatPerception.PlayerState): BlockUseTarget? {
        val fakePlayer = clientInstance.fakePlayer
        val leadTicks =
                when {
                    enemy.distance2D <= 2.8 -> 0.25
                    enemy.distance2D <= 4.2 -> 0.45
                    else -> 0.65
                }
        val predictedX = enemy.x + enemy.velocityX * leadTicks
        val predictedZ = enemy.z + enemy.velocityZ * leadTicks
        val baseY = floor(enemy.y)
        val centerX = floor(predictedX)
        val centerZ = floor(predictedZ)
        var best: BlockUseTarget? = null

        for (x in (centerX - 1)..(centerX + 1)) {
            for (z in (centerZ - 1)..(centerZ + 1)) {
                val placeY = findPlaceableFluidY(x, z, baseY) ?: continue
                val aimX = x + 0.5
                val aimY = placeY.toDouble()
                val aimZ = z + 0.5
                val selfDistance2D = sqrt(
                        (aimX - fakePlayer.x) * (aimX - fakePlayer.x) +
                                (aimZ - fakePlayer.z) * (aimZ - fakePlayer.z)
                )
                val eyeY = interactionEyeY()
                val eyeDistance = sqrt(
                        (aimX - fakePlayer.x) * (aimX - fakePlayer.x) +
                                (aimY - eyeY) * (aimY - eyeY) +
                                (aimZ - fakePlayer.z) * (aimZ - fakePlayer.z)
                )
                if (selfDistance2D < 1.15 || eyeDistance > MAX_BUCKET_USE_DISTANCE) continue
                if (!hasClearUseLineTo(aimX, aimY + 0.03, aimZ)) continue

                val predictedError = sqrt(
                        (aimX - predictedX) * (aimX - predictedX) +
                                (aimZ - predictedZ) * (aimZ - predictedZ)
                )
                val currentError = sqrt(
                        (aimX - enemy.x) * (aimX - enemy.x) +
                                (aimZ - enemy.z) * (aimZ - enemy.z)
                )
                val score = predictedError + currentError * 0.35 + selfDistance2D * 0.04
                if (best == null || score < best!!.score) {
                    best = BlockUseTarget(x, placeY, z, aimX, aimY, aimZ, score)
                }
            }
        }

        return best
    }

    private fun isLavaPlacementTargetUsable(target: BlockUseTarget): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world
        val targetId = world.getBlockAt(target.blockX, target.blockY, target.blockZ).id
        val belowId = world.getBlockAt(target.blockX, target.blockY - 1, target.blockZ).id
        if (!isReplaceableForFluid(targetId) || isFluidBlock(belowId)) return false
        if (!hasSolidSupport(target.blockX, target.blockY - 1, target.blockZ)) return false

        val dx = target.aimX - fakePlayer.x
        val dy = target.aimY - interactionEyeY()
        val dz = target.aimZ - fakePlayer.z
        val horizontalDistance = sqrt(dx * dx + dz * dz)
        val eyeDistance = sqrt(dx * dx + dy * dy + dz * dz)
        if (horizontalDistance < 1.15 || eyeDistance > MAX_BUCKET_USE_DISTANCE) return false

        return hasClearUseLineTo(target.aimX, target.aimY + 0.03, target.aimZ)
    }

    private fun findNearestRecoverableFluid(radius: Double = FLUID_RECOVERY_RANGE): FluidTarget? {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world
        val baseX = floor(fakePlayer.x)
        val baseY = floor(fakePlayer.y)
        val baseZ = floor(fakePlayer.z)
        var best: FluidTarget? = null

        for (x in -3..3) {
            for (y in -2..2) {
                for (z in -3..3) {
                    val bx = baseX + x
                    val by = baseY + y
                    val bz = baseZ + z
                    val id = world.getBlockAt(bx, by, bz).id
                    if (!isFluidBlock(id)) continue
                    val aimX = bx + 0.5
                    val aimY = by + 0.45
                    val aimZ = bz + 0.5
                    val dist =
                            sqrt(
                                    (aimX - fakePlayer.x) * (aimX - fakePlayer.x) +
                                            (aimY - fakePlayer.y) * (aimY - fakePlayer.y) +
                                            (aimZ - fakePlayer.z) * (aimZ - fakePlayer.z)
                            )
                    if (dist <= radius && hasClearUseLineTo(aimX, aimY, aimZ)) {
                        val sourceBias =
                                if (id == Block.WATER_STILL || id == Block.LAVA_STILL) 0.0 else 0.35
                        val score = dist + sourceBias
                        if (best == null || score < best!!.score) {
                            best = FluidTarget(aimX, aimY, aimZ, score)
                        }
                    }
                }
            }
        }

        if (best != null) return best

        if (!placedLavaX.isNaN()) {
            val id = world.getBlockAt(placedLavaX, placedLavaY, placedLavaZ).id
            if (id == Block.LAVA_FLOWING || id == Block.LAVA_STILL) {
                val aimX = placedLavaX + 0.5
                val aimY = placedLavaY + 0.45
                val aimZ = placedLavaZ + 0.5
                val dist =
                        sqrt(
                                (aimX - fakePlayer.x) * (aimX - fakePlayer.x) +
                                        (aimY - fakePlayer.y) * (aimY - fakePlayer.y) +
                                        (aimZ - fakePlayer.z) * (aimZ - fakePlayer.z)
                        )
                if (dist <= radius && hasClearUseLineTo(aimX, aimY, aimZ)) {
                    return FluidTarget(aimX, aimY, aimZ, 0.0)
                }
            }
        }
        return null
    }

    private fun isRecoverableFluidTargetUsable(
        target: FluidTarget,
        radius: Double = FLUID_RECOVERY_RANGE
    ): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val blockX = floor(target.x)
        val blockY = floor(target.y)
        val blockZ = floor(target.z)
        if (!isFluidBlock(fakePlayer.world.getBlockAt(blockX, blockY, blockZ).id)) return false

        val dx = target.x - fakePlayer.x
        val dy = target.y - fakePlayer.y
        val dz = target.z - fakePlayer.z
        if (sqrt(dx * dx + dy * dy + dz * dz) > radius) return false

        return hasClearUseLineTo(target.x, target.y, target.z)
    }

    private fun shouldRecoverPlacedFluid(enemy: CombatPerception.PlayerState?): Boolean {
        val now = clientInstance.currentTick
        if (!hasEmptyBucket()) return false
        if (enemy != null && enemy.distance3D <= FLUID_RECOVERY_MIN_ENEMY_DISTANCE) return false
        if (now - placedLavaTick < 8 || now - placedWaterTick < 8) return false
        return findNearestRecoverableFluid(FLUID_RECOVERY_RANGE) != null
    }

    private fun tryRecoverFluid(tick: Tick, inventory: gg.mineral.bot.api.inv.Inventory): Boolean {
        val bucketSlot = getBucketRecoverySlot()
        if (bucketSlot == -1) return false

        tick.prerequisite("Bucket In Hotbar", bucketSlot <= 8) { moveItemToHotbar(bucketSlot, inventory) }
        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }
        tick.prerequisite("Holding Bucket", inventory.heldSlot == resolveHotbarSlot(bucketSlot)) {
            selectHotbarSlot(resolveHotbarSlot(bucketSlot))
        }

        tick.execute {
            var target = pendingRecoveryTarget
            if (target == null || !isRecoverableFluidTargetUsable(target)) {
                pendingRecoveryTarget = findNearestRecoverableFluid()
                recoveryAimStartTick = clientInstance.currentTick
                target = pendingRecoveryTarget
            }

            if (target == null || !isRecoverableFluidTargetUsable(target)) {
                pendingRecoveryTarget = null
                recoveryAimStartTick = -1
                return@execute
            }

            val rawAngles = anglesToPoint(target.x, target.y, target.z)
            val angles = AimAngles(rawAngles.yaw, pitchToRecoveryTarget(target))
            aimAt(angles)

            if (clientInstance.currentTick <= recoveryAimStartTick || !isAimAligned(angles)) {
                return@execute
            }

            pressButton(55, MouseButton.Type.RIGHT_CLICK)
            pendingRecoveryTarget = null
            recoveryAimStartTick = -1
            lockAction(4)
        }
        return true
    }

    private fun handleEmergencyWater(tick: Tick, inventory: gg.mineral.bot.api.inv.Inventory): Boolean {
        val waterSlot =
            when (waterState) {
                WaterState.IDLE, WaterState.AIMING_TO_PLACE -> getWaterPlacementSlot()
                WaterState.WAIT_TO_PICKUP, WaterState.AIMING_TO_PICKUP -> getBucketRecoverySlot()
            }
        if (waterSlot == -1) {
            if (waterState != WaterState.IDLE && clientInstance.currentTick - waterStateStartTick < 16) {
                lockAction(2)
                return true
            }
            waterState = WaterState.IDLE
            return false
        }

        tick.prerequisite("Water Control In Hotbar", waterSlot <= 8) {
            moveItemToHotbar(waterSlot, inventory)
        }
        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }
        tick.prerequisite("Holding Water Control", inventory.heldSlot == resolveHotbarSlot(waterSlot)) {
            selectHotbarSlot(resolveHotbarSlot(waterSlot))
        }

        tick.execute {
            if (waterState == WaterState.IDLE) {
                waterState = WaterState.AIMING_TO_PLACE
                waterAimStartTick = clientInstance.currentTick
                waterStateStartTick = clientInstance.currentTick
            }

            setMousePitch(EMERGENCY_WATER_PITCH)
            val pitchAligned =
                kotlin.math.abs(
                    angleDifference(clientInstance.fakePlayer.pitch, EMERGENCY_WATER_PITCH)
                ) <= EMERGENCY_WATER_PITCH_TOLERANCE

            if (waterState == WaterState.AIMING_TO_PLACE) {
                if (clientInstance.currentTick <= waterAimStartTick || !pitchAligned) {
                    return@execute
                }

                pressButton(80, MouseButton.Type.RIGHT_CLICK)
                waterState = WaterState.WAIT_TO_PICKUP
                waterStateStartTick = clientInstance.currentTick
                waterAimStartTick = -1
                placedWaterTick = clientInstance.currentTick
                lockAction(8)
                return@execute
            }

            if (waterState == WaterState.WAIT_TO_PICKUP &&
                    clientInstance.currentTick - waterStateStartTick >= 6
            ) {
                waterState = WaterState.AIMING_TO_PICKUP
                waterAimStartTick = clientInstance.currentTick
                return@execute
            }

            if (waterState == WaterState.AIMING_TO_PICKUP) {
                if (clientInstance.currentTick <= waterAimStartTick || !pitchAligned) {
                    return@execute
                }

                pressButton(80, MouseButton.Type.RIGHT_CLICK)
                waterState = WaterState.IDLE
                waterAimStartTick = -1
                lockAction(6)
            }
        }

        return true
    }

    override fun onTick(tick: Tick) {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        val enemy = getClosestEnemyState()
        var actionTaken = false

        keepForward()

        if (handleOngoingEat(tick, inventory, enemy)) {
            return
        }

        if (needsEmergencyWater()) {
            if (handleEmergencyWater(tick, inventory)) {
                actionTaken = true
                return
            }
        }

        if (canStartAction() && shouldRecoverPlacedFluid(enemy)) {
            if (tryRecoverFluid(tick, inventory)) return
        }

        if (!preFightGappleUsed &&
                clientInstance.currentTick - matchStartTick >= 80 &&
                hasNormalGapple() &&
                (enemy == null || enemy.distance3D > 10.0)
        ) {
            val openerGapple = getGoldenAppleSlot(preferHead = false)
            if (openerGapple != -1) {
                tick.prerequisite("Opener Gapple In Hotbar", openerGapple <= 8) {
                    moveItemToHotbar(openerGapple, inventory)
                }
                tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
                    pressKey(10, Key.Type.KEY_ESCAPE)
                }
                tick.prerequisite("Holding Opener Gapple", inventory.heldSlot == resolveHotbarSlot(openerGapple)) {
                    selectHotbarSlot(resolveHotbarSlot(openerGapple))
                }
                tick.execute {
                    pressButton(MouseButton.Type.RIGHT_CLICK)
                    preFightGappleUsed = true
                    beginEating(EatState.OPENER_GAPPLE)
                }
                return
            }
        }

        if (needsGoldenHead() && canEatHeadNow(fakePlayer.health)) {
            if (!isSafeToEat(enemy)) {
                if (enemy != null && tryCreateEatWindow(tick, enemy, inventory)) return
            }
            val headSlot = getGoldenAppleSlot(preferHead = true)
            if (headSlot != -1) {
                tick.prerequisite("Head In Hotbar", headSlot <= 8) {
                    moveItemToHotbar(headSlot, inventory)
                }
                tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
                    pressKey(10, Key.Type.KEY_ESCAPE)
                }
                tick.prerequisite("Holding Head", inventory.heldSlot == resolveHotbarSlot(headSlot)) {
                    selectHotbarSlot(resolveHotbarSlot(headSlot))
                }
                tick.execute {
                    pressButton(MouseButton.Type.RIGHT_CLICK)
                    keepForward()
                    beginEating(EatState.GOLDEN_HEAD)
                    lastHeadEatTick = clientInstance.currentTick
                }
                return
            }
        }

        if (needsGoldenApple() && fakePlayer.health < GAPPLE_EAT_THRESHOLD && canEatGappleNow(fakePlayer.health)) {
            // Intentionally avoid the low-health rod opener here so BuildUHC gapple timing stays smoother.
            // if (!isSafeToEat(enemy)) {
            //     if (enemy != null && tryCreateEatWindow(tick, enemy, inventory)) return
            // }
            val gappleSlot = getGoldenAppleSlot()
            if (gappleSlot != -1) {
                tick.prerequisite("Gapple In Hotbar", gappleSlot <= 8) {
                    moveItemToHotbar(gappleSlot, inventory)
                }
                tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
                    pressKey(10, Key.Type.KEY_ESCAPE)
                }
                tick.prerequisite("Holding Gapple", inventory.heldSlot == resolveHotbarSlot(gappleSlot)) {
                    selectHotbarSlot(resolveHotbarSlot(gappleSlot))
                }
                tick.execute {
                    pressButton(MouseButton.Type.RIGHT_CLICK)
                    keepForward()
                    beginEating(EatState.GOLDEN_APPLE)
                    lastGappleEatTick = clientInstance.currentTick
                }
                return
            }
        }

        if (canStartAction() &&
                enemy != null &&
                (pendingLavaTarget != null || shouldPlaceLava(enemy))
        ) {
            val lavaSlot = getLavaSlot()
            if (lavaSlot != -1) {
                tick.prerequisite("Lava In Hotbar", lavaSlot <= 8) {
                    moveItemToHotbar(lavaSlot, inventory)
                }
                tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
                    pressKey(10, Key.Type.KEY_ESCAPE)
                }
                tick.prerequisite("Holding Lava", inventory.heldSlot == resolveHotbarSlot(lavaSlot)) {
                    selectHotbarSlot(resolveHotbarSlot(lavaSlot))
                }
                tick.execute {
                    var target = pendingLavaTarget
                    if (target == null || !isLavaPlacementTargetUsable(target)) {
                        pendingLavaTarget = findLavaPlacementTarget(enemy)
                        lavaAimStartTick = clientInstance.currentTick
                        target = pendingLavaTarget
                    }

                    if (target == null || !isLavaPlacementTargetUsable(target)) {
                        pendingLavaTarget = null
                        lavaAimStartTick = -1
                        return@execute
                    }

                    val angles = anglesToPoint(target.aimX, target.aimY + 0.03, target.aimZ)
                    val pitch = (angles.pitch + 4f).coerceIn(18f, 82f)
                    val aimTarget = AimAngles(angles.yaw, pitch)

                    aimAt(aimTarget)

                    if (clientInstance.currentTick <= lavaAimStartTick ||
                            !isAimAligned(aimTarget)
                    ) {
                        return@execute
                    }

                    pressButton(50, MouseButton.Type.RIGHT_CLICK)
                    pendingLavaTarget = null
                    lavaAimStartTick = -1
                    lastLavaPlaceTick = clientInstance.currentTick
                    placedLavaTick = clientInstance.currentTick
                    placedLavaX = target.blockX.toDouble()
                    placedLavaY = target.blockY.toDouble()
                    placedLavaZ = target.blockZ.toDouble()
                    lockAction(14)
                    actionTaken = true
                }
                return
            }
        }

        unpressButton(MouseButton.Type.RIGHT_CLICK)
        if (!actionTaken) finish()
    }

    override fun blocksContinuousAim(): Boolean {
        return executing
    }

    override fun blocksContinuousAttack(): Boolean {
        return executing
    }

    override fun blocksContinuousMovement(): Boolean {
        return isEatingApple() || waterState != WaterState.IDLE || clientInstance.currentTick <= actionLockUntilTick
    }

    override fun debugSummary(): String {
        return "eatState=$eatState,waterState=$waterState,actionLockRemaining=${actionLockUntilTick - clientInstance.currentTick},preFightGappleUsed=$preFightGappleUsed,lastGappleAgo=${clientInstance.currentTick - lastGappleEatTick},lastHeadAgo=${clientInstance.currentTick - lastHeadEatTick}"
    }

    override fun onEnd() {
        clearEatingState()
        unpressKey(Key.Type.KEY_A, Key.Type.KEY_D)
        waterState = WaterState.IDLE
        waterAimStartTick = -1
        pendingLavaTarget = null
        lavaAimStartTick = -1
        pendingRecoveryTarget = null
        recoveryAimStartTick = -1
    }

    override fun onEvent(event: Event): Boolean {
        return false
    }

    override fun onGameLoop() {
        if (!isEatingApple() && clientInstance.currentTick > actionLockUntilTick + 8) {
            unpressButton(MouseButton.Type.RIGHT_CLICK)
        }
    }
}
