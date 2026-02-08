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

/**
 * BuildUHC Combat Goal for strategic block/lava placement and golden apple usage.
 *
 * Key strategies:
 * - Use fishing rod for knockback and distance advantage
 * - Place lava to damage enemies
 * - Place blocks for defense/positioning
 * - Eat golden apple when low health
 * - Eat golden head (golden apple with durability 1) when critically low
 */
class BuildUHCCombatGoal(clientInstance: ClientInstance) :
        InventoryGoal(clientInstance), Sporadic, Timebound {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 200

    private var lastLavaPlaceTick = 0
    private var actionLockUntilTick = 0
    private var matchStartTick = -1
    private var preFightGappleUsed = false

    override fun shouldExecute(): Boolean {
        if (matchStartTick == -1) {
            matchStartTick = clientInstance.currentTick
        }

        if (needsEmergencyWater()) return true

        // BuildUHC opening: around 4s after spawn, pre-gap once then start full fight.
        if (!preFightGappleUsed && clientInstance.currentTick - matchStartTick >= 80 && hasNormalGapple()) {
            return true
        }

        val enemy = getClosestEnemy() ?: return false
        val fakePlayer = clientInstance.fakePlayer
        val distance = fakePlayer.distance3DTo(enemy)

        // Only interrupt melee when there is an actionable utility play.
        if (needsGoldenHead()) return true
        if (needsGoldenApple() && fakePlayer.health < 10) return true

        return hasLava() && distance in 2.0..5.0 && fakePlayer.isOnGround
    }

    override fun onStart() {
        actionLockUntilTick = 0
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

    private fun hasBlocks(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        // Check for common building blocks
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            val id = item.item.id
            // Common building blocks: cobblestone (4), stone (1), dirt (3), planks (5), etc.
            if (id in 1..5 || id == 24 || id == 45 || id == 48 || id == 98) return true
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

        // Golden head is a golden apple with durability 1
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
            if (entity is ClientPlayer &&
                            !clientInstance.configuration.friendlyUUIDs.contains(entity.uuid)
            ) {
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
                // Golden head has durability 1
                if (preferHead && item.durability == 1) return i
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

    /** Place lava when enemy is close and we have distance to escape. */
    private fun shouldPlaceLava(): Boolean {
        if (clientInstance.currentTick - lastLavaPlaceTick < 100) return false
        if (!hasLava()) return false

        val enemy = getClosestEnemy() ?: return false
        val fakePlayer = clientInstance.fakePlayer
        val distance = fakePlayer.distance3DTo(enemy)

        // Place lava when enemy is approaching (2-5 blocks)
        return distance >= 2.0 && distance <= 5.0 && fakePlayer.isOnGround
    }

    private fun isInDangerousBlock(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world

        val feet = world.getBlockAt(fakePlayer.x, fakePlayer.y, fakePlayer.z).id
        val body = world.getBlockAt(fakePlayer.x, fakePlayer.y + 0.8, fakePlayer.z).id
        return feet == gg.mineral.bot.api.world.block.Block.LAVA_FLOWING ||
                feet == gg.mineral.bot.api.world.block.Block.LAVA_STILL ||
                feet == gg.mineral.bot.api.world.block.Block.FIRE ||
                body == gg.mineral.bot.api.world.block.Block.LAVA_FLOWING ||
                body == gg.mineral.bot.api.world.block.Block.LAVA_STILL ||
                body == gg.mineral.bot.api.world.block.Block.FIRE
    }

    private fun needsEmergencyWater(): Boolean {
        return isInDangerousBlock() && hasWater()
    }

    override fun onTick(tick: Tick) {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        val enemy = getClosestEnemy()
        var actionTaken = false

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen !is ContainerScreen) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        // Let base melee keep control most ticks.
        keepForward()

        // Priority 0: emergency extinguish / pickup
        if (needsEmergencyWater()) {
            val waterSlot = getWaterControlSlot()
            if (waterSlot != -1) {
                tick.prerequisite("Water Control In Hotbar", waterSlot <= 8) {
                    moveItemToHotbar(waterSlot, inventory)
                }
                tick.prerequisite("Holding Water Control", inventory.heldSlot == resolveHotbarSlot(waterSlot)) {
                    selectHotbarSlot(resolveHotbarSlot(waterSlot))
                }
                tick.execute {
                    setMousePitch(88f)
                    pressButton(25, MouseButton.Type.RIGHT_CLICK)
                    lockAction(6)
                    actionTaken = true
                }
            }
        }

        if (!preFightGappleUsed && clientInstance.currentTick - matchStartTick >= 80 && hasNormalGapple()) {
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
                    actionTaken = true
                }
            }
        }

        // Priority 1: Eat golden head if critically low
        if (needsGoldenHead()) {
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
                    actionTaken = true
                }
                return
            }
        }

        // Priority 2: Eat golden apple if low health
        if (needsGoldenApple() && fakePlayer.health < 10) {
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
                    actionTaken = true
                }
                return
            }
        }

        // Priority 3: Place lava
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
                    // Look at ground in front of enemy
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
                    setMousePitch(45f) // Look down

                    if (!isAimAligned(fakePlayer.yaw, yaw, 10f)) {
                        return@execute
                    }

                    pressButton(50, MouseButton.Type.RIGHT_CLICK)
                    lastLavaPlaceTick = clientInstance.currentTick
                    lockAction(14)
                    actionTaken = true
                }
                return
            }
        }

        // If there is no utility action this tick, release right click so we don't get stuck.
        unpressButton(MouseButton.Type.RIGHT_CLICK)

        // BuildUHC utility should be burst-like; give control back to melee quickly.
        if (!actionTaken) {
            finish()
        }
    }

    override fun onEnd() {
        unpressButton(MouseButton.Type.RIGHT_CLICK)
        heldUtilitySlot = -1
    }

    override fun onEvent(event: Event): Boolean {
        return false
    }

    override fun onGameLoop() {
        if (clientInstance.currentTick > actionLockUntilTick + 8) {
            unpressButton(MouseButton.Type.RIGHT_CLICK)
        }

        // Prevent stale held-use from carrying forever into other goals.
        if (heldUtilitySlot != -1 && clientInstance.currentTick > actionLockUntilTick + 10) {
            unpressButton(MouseButton.Type.RIGHT_CLICK)
            heldUtilitySlot = -1
        }
    }
}
