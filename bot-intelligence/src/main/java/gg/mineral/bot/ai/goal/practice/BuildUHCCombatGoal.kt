package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.living.player.ClientPlayer
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.screen.type.ContainerScreen
import gg.mineral.bot.api.world.block.Block

class BuildUHCCombatGoal(clientInstance: ClientInstance) :
        InventoryGoal(clientInstance), Sporadic, Timebound {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 42

    private var lastLavaPlaceTick = 0
    private var actionLockUntilTick = 0
    private var matchStartTick = -1
    private var preFightGappleUsed = false
    private var waterState = WaterState.IDLE
    private var waterStateStartTick = 0
    private var lastGappleEatTick = -200
    private var lastHeadEatTick = -200
    private var placedLavaTick = -200
    private var placedWaterTick = -200
    private var placedLavaX = Double.NaN
    private var placedLavaY = Double.NaN
    private var placedLavaZ = Double.NaN

    private enum class WaterState {
        IDLE,
        WAIT_TO_PICKUP
    }

    override fun shouldExecute(): Boolean {
        if (matchStartTick == -1) {
            matchStartTick = clientInstance.currentTick
        }

        val fakePlayer = clientInstance.fakePlayer
        val enemy = getClosestEnemy()

        if (needsEmergencyWater()) return true

        // BuildUHC opening: around 4s after spawn, pre-gap once when enemy is not already close.
        if (!preFightGappleUsed &&
                clientInstance.currentTick - matchStartTick >= 80 &&
                hasNormalGapple() &&
                (enemy == null || fakePlayer.distance3DTo(enemy) > 10.0)
        ) {
            return true
        }

        enemy ?: return false
        val distance = fakePlayer.distance3DTo(enemy)

        if (needsGoldenHead() && canEatHeadNow(fakePlayer.health)) return true
        if (needsGoldenApple() && fakePlayer.health < 10 && canEatGappleNow(fakePlayer.health)) return true
        if (shouldRecoverPlacedFluid()) return true

        return hasLava() && distance in 1.9..5.3 && fakePlayer.isOnGround && !allBucketsEmpty()
    }

    override fun onStart() {
        actionLockUntilTick = 0
        waterState = WaterState.IDLE
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

    private fun isAimAligned(currentYaw: Float, targetYaw: Float, tolerance: Float = 8f): Boolean {
        return kotlin.math.abs(angleDifference(currentYaw, targetYaw)) <= tolerance
    }

    private fun hasLava(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        return inventory.contains(Item.LAVA_BUCKET)
    }

    private fun hasWater(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        return inventory.contains(Item.WATER_BUCKET) || inventory.contains(Item.BUCKET)
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

    private fun getClosestEnemy(): ClientPlayer? {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world
        val targetSearchRange = clientInstance.configuration.targetSearchRange

        var closestTarget: ClientPlayer? = null
        var closestDistance = Double.MAX_VALUE

        for (entity in world.entities) {
            if (entity is ClientPlayer && !clientInstance.configuration.friendlyUUIDs.contains(entity.uuid)) {
                val distance = fakePlayer.distance3DTo(entity)
                if (distance <= targetSearchRange && distance < closestDistance) {
                    closestDistance = distance
                    closestTarget = entity
                }
            }
        }
        return closestTarget
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

    private fun getWaterControlSlot(): Int {
        val inventory = clientInstance.fakePlayer.inventory
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.WATER_BUCKET || item.item.id == Item.BUCKET) return i
        }
        return -1
    }

    private fun shouldPlaceLava(): Boolean {
        if (clientInstance.currentTick - lastLavaPlaceTick < 100) return false
        if (!hasLava()) return false

        val enemy = getClosestEnemy() ?: return false
        val fakePlayer = clientInstance.fakePlayer
        val distance = fakePlayer.distance3DTo(enemy)

        return distance >= 1.9 && distance <= 5.3 && fakePlayer.isOnGround && !allBucketsEmpty()
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

    private fun needsEmergencyWater(): Boolean {
        if (allBucketsEmpty()) return false
        return hasWater() && (isInDangerousBlock() || waterState != WaterState.IDLE)
    }

    private fun canEatGappleNow(health: Float): Boolean {
        if (health <= 3.0f) return true
        return clientInstance.currentTick - lastGappleEatTick >= 100
    }

    private fun canEatHeadNow(health: Float): Boolean {
        if (health <= 2.5f) return true
        return clientInstance.currentTick - lastHeadEatTick >= 200
    }

    private fun isSafeToEat(enemy: ClientPlayer?): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        if (enemy == null) return true
        if (fakePlayer.health <= 2.5f) return true
        return fakePlayer.distance3DTo(enemy) >= 4.4
    }

    private fun getRodSlot(): Int {
        val inventory = clientInstance.fakePlayer.inventory
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.FISHING_ROD) return i
        }
        return -1
    }

    private fun tryCreateEatWindow(tick: Tick, enemy: ClientPlayer, inventory: gg.mineral.bot.api.inv.Inventory): Boolean {
        val rodSlot = getRodSlot()
        if (rodSlot == -1) return false

        tick.prerequisite("Rod In Hotbar", rodSlot <= 8) { moveItemToHotbar(rodSlot, inventory) }
        tick.prerequisite("Holding Rod", inventory.heldSlot == resolveHotbarSlot(rodSlot)) {
            selectHotbarSlot(resolveHotbarSlot(rodSlot))
        }

        tick.execute {
            val bot = clientInstance.fakePlayer
            val lateralBoost = (enemy.x - enemy.lastX) * 3.2
            val forwardBoost = (enemy.z - enemy.lastZ) * 3.2
            val predictedX = enemy.x + lateralBoost
            val predictedY = enemy.y + enemy.eyeHeight * 0.57
            val predictedZ = enemy.z + forwardBoost

            val predictedEnemy = object : ClientPlayer by enemy {
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

    private data class FluidTarget(val x: Double, val y: Double, val z: Double)

    private fun findNearestRecoverableFluid(radius: Double = 3.2): FluidTarget? {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world
        var best: FluidTarget? = null
        var bestDist = Double.MAX_VALUE

        for (x in -3..3) {
            for (y in -2..2) {
                for (z in -3..3) {
                    val bx = fakePlayer.x + x
                    val by = fakePlayer.y + y
                    val bz = fakePlayer.z + z
                    val id = world.getBlockAt(bx, by, bz).id
                    if (id != Block.WATER_FLOWING && id != Block.WATER_STILL && id != Block.LAVA_FLOWING && id != Block.LAVA_STILL) continue
                    val dist = sqrt((bx - fakePlayer.x) * (bx - fakePlayer.x) + (by - fakePlayer.y) * (by - fakePlayer.y) + (bz - fakePlayer.z) * (bz - fakePlayer.z))
                    if (dist <= radius && dist < bestDist) {
                        bestDist = dist
                        best = FluidTarget(bx + 0.5, by + 0.06, bz + 0.5)
                    }
                }
            }
        }

        if (best != null) return best

        if (!placedLavaX.isNaN()) {
            val id = world.getBlockAt(placedLavaX, placedLavaY, placedLavaZ).id
            if (id == Block.LAVA_FLOWING || id == Block.LAVA_STILL) {
                return FluidTarget(placedLavaX + 0.5, placedLavaY + 0.06, placedLavaZ + 0.5)
            }
        }
        return null
    }

    private fun shouldRecoverPlacedFluid(): Boolean {
        val now = clientInstance.currentTick
        if (!clientInstance.fakePlayer.inventory.contains(Item.BUCKET)) return false
        if (now - placedLavaTick < 80 && now - placedWaterTick < 80) return false
        return findNearestRecoverableFluid() != null
    }

    private fun tryRecoverFluid(tick: Tick, inventory: gg.mineral.bot.api.inv.Inventory): Boolean {
        val bucketSlot = getWaterControlSlot()
        if (bucketSlot == -1) return false

        tick.prerequisite("Bucket In Hotbar", bucketSlot <= 8) { moveItemToHotbar(bucketSlot, inventory) }
        tick.prerequisite("Holding Bucket", inventory.heldSlot == resolveHotbarSlot(bucketSlot)) {
            selectHotbarSlot(resolveHotbarSlot(bucketSlot))
        }

        tick.execute {
            val fakePlayer = clientInstance.fakePlayer
            val target = findNearestRecoverableFluid()
            if (target != null) {
                val dx = target.x - fakePlayer.x
                val dy = target.y - (fakePlayer.y + fakePlayer.eyeHeight)
                val dz = target.z - fakePlayer.z
                val horizDist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001)
                val yaw = Math.toDegrees(-fastArcTan(dx / dz)).toFloat().let {
                    when {
                        dz < 0 && dx < 0 -> (90 + Math.toDegrees(fastArcTan(dz / dx))).toFloat()
                        dz < 0 && dx > 0 -> (-90 + Math.toDegrees(fastArcTan(dz / dx))).toFloat()
                        else -> it
                    }
                }
                val pitch = Math.toDegrees(-fastArcTan(dy / horizDist)).toFloat().coerceIn(60f, 89f)
                setMouseYaw(yaw)
                setMousePitch(pitch)
            } else {
                setMousePitch(86f)
            }
            pressButton(70, MouseButton.Type.RIGHT_CLICK)
            lockAction(4)
        }
        return true
    }

    private fun handleEmergencyWater(tick: Tick, inventory: gg.mineral.bot.api.inv.Inventory): Boolean {
        val waterSlot = getWaterControlSlot()
        if (waterSlot == -1) {
            waterState = WaterState.IDLE
            return false
        }

        tick.prerequisite("Water Control In Hotbar", waterSlot <= 8) {
            moveItemToHotbar(waterSlot, inventory)
        }
        tick.prerequisite("Holding Water Control", inventory.heldSlot == resolveHotbarSlot(waterSlot)) {
            selectHotbarSlot(resolveHotbarSlot(waterSlot))
        }

        tick.execute {
            setMousePitch(88f)

            if (waterState == WaterState.IDLE) {
                // Place water first.
                pressButton(80, MouseButton.Type.RIGHT_CLICK)
                waterState = WaterState.WAIT_TO_PICKUP
                waterStateStartTick = clientInstance.currentTick
                placedWaterTick = clientInstance.currentTick
                lockAction(8)
                return@execute
            }

            // Wait ~0.3s (6 ticks) before trying to pick water back up.
            if (clientInstance.currentTick - waterStateStartTick >= 6) {
                pressButton(80, MouseButton.Type.RIGHT_CLICK)
                waterState = WaterState.IDLE
                lockAction(6)
            }
        }

        return true
    }

    override fun onTick(tick: Tick) {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        val enemy = getClosestEnemy()
        var actionTaken = false

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen !is ContainerScreen) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        keepForward()

        if (needsEmergencyWater()) {
            if (handleEmergencyWater(tick, inventory)) {
                actionTaken = true
                return
            }
        }

        if (canStartAction() && shouldRecoverPlacedFluid()) {
            if (tryRecoverFluid(tick, inventory)) return
        }

        if (!preFightGappleUsed &&
                clientInstance.currentTick - matchStartTick >= 80 &&
                hasNormalGapple() &&
                (enemy == null || fakePlayer.distance3DTo(enemy) > 10.0)
        ) {
            val openerGapple = getGoldenAppleSlot(preferHead = false)
            if (openerGapple != -1) {
                tick.prerequisite("Opener Gapple In Hotbar", openerGapple <= 8) {
                    moveItemToHotbar(openerGapple, inventory)
                }
                tick.prerequisite("Holding Opener Gapple", inventory.heldSlot == resolveHotbarSlot(openerGapple)) {
                    selectHotbarSlot(resolveHotbarSlot(openerGapple))
                }
                tick.execute {
                    pressButton(MouseButton.Type.RIGHT_CLICK)
                    preFightGappleUsed = true
                    lockAction(12)
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
                tick.prerequisite("Holding Head", inventory.heldSlot == resolveHotbarSlot(headSlot)) {
                    selectHotbarSlot(resolveHotbarSlot(headSlot))
                }
                tick.execute {
                    pressButton(MouseButton.Type.RIGHT_CLICK)
                    keepForward()
                    lockAction(10)
                    lastHeadEatTick = clientInstance.currentTick
                }
                return
            }
        }

        if (needsGoldenApple() && fakePlayer.health < 10 && canEatGappleNow(fakePlayer.health)) {
            if (!isSafeToEat(enemy)) {
                if (enemy != null && tryCreateEatWindow(tick, enemy, inventory)) return
            }
            val gappleSlot = getGoldenAppleSlot()
            if (gappleSlot != -1) {
                tick.prerequisite("Gapple In Hotbar", gappleSlot <= 8) {
                    moveItemToHotbar(gappleSlot, inventory)
                }
                tick.prerequisite("Holding Gapple", inventory.heldSlot == resolveHotbarSlot(gappleSlot)) {
                    selectHotbarSlot(resolveHotbarSlot(gappleSlot))
                }
                tick.execute {
                    pressButton(MouseButton.Type.RIGHT_CLICK)
                    keepForward()
                    lockAction(10)
                    lastGappleEatTick = clientInstance.currentTick
                }
                return
            }
        }

        if (canStartAction() && shouldPlaceLava() && enemy != null) {
            val lavaSlot = getLavaSlot()
            if (lavaSlot != -1) {
                tick.prerequisite("Lava In Hotbar", lavaSlot <= 8) {
                    moveItemToHotbar(lavaSlot, inventory)
                }
                tick.prerequisite("Holding Lava", inventory.heldSlot == resolveHotbarSlot(lavaSlot)) {
                    selectHotbarSlot(resolveHotbarSlot(lavaSlot))
                }
                tick.execute {
                    val midX = (fakePlayer.x + enemy.x) / 2
                    val midZ = (fakePlayer.z + enemy.z) / 2
                    val x = midX - fakePlayer.x
                    val z = midZ - fakePlayer.z

                    var yaw = Math.toDegrees(-fastArcTan(x / z)).toFloat()
                    if (z < 0.0 && x < 0.0)
                            yaw = (90.0 + Math.toDegrees(fastArcTan(z / x))).toFloat()
                    else if (z < 0.0 && x > 0.0)
                            yaw = (-90.0 + Math.toDegrees(fastArcTan(z / x))).toFloat()

                    setMouseYaw(yaw)
                    setMousePitch(45f)

                    if (!isAimAligned(fakePlayer.yaw, yaw, 10f)) {
                        return@execute
                    }

                    pressButton(50, MouseButton.Type.RIGHT_CLICK)
                    lastLavaPlaceTick = clientInstance.currentTick
                    placedLavaTick = clientInstance.currentTick
                    placedLavaX = midX
                    placedLavaY = fakePlayer.y - 1.0
                    placedLavaZ = midZ
                    lockAction(14)
                    actionTaken = true
                }
                return
            }
        }

        unpressButton(MouseButton.Type.RIGHT_CLICK)
        if (!actionTaken) finish()
    }

    override fun onEnd() {
        unpressButton(MouseButton.Type.RIGHT_CLICK)
        unpressKey(Key.Type.KEY_A, Key.Type.KEY_D)
        waterState = WaterState.IDLE
    }

    override fun onEvent(event: Event): Boolean {
        return false
    }

    override fun onGameLoop() {
        if (clientInstance.currentTick > actionLockUntilTick + 8) {
            unpressButton(MouseButton.Type.RIGHT_CLICK)
        }
    }
}
