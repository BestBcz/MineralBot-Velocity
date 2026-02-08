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
 * Fishing Rod Combat Goal for creating distance advantages.
 *
 * Uses fishing rod to:
 * - Pull enemies towards you for combos
 * - Knock enemies back when they're attacking
 * - Create gap to escape or pot
 */
class FishingRodGoal(clientInstance: ClientInstance) :
        InventoryGoal(clientInstance), Sporadic, Timebound {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 50

    private var lastRodTick = 0
    private var rodState = RodState.IDLE

    private enum class RodState {
        IDLE,
        THROWING,
        IN_FLIGHT,
        REELING
    }

    override fun shouldExecute(): Boolean {
        // Rod cooldown (about 2 seconds)
        if (clientInstance.currentTick - lastRodTick < 24) return false

        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        if (!inventory.contains(Item.FISHING_ROD)) return false

        val enemy = getClosestEnemy() ?: return false
        val distance = fakePlayer.distance3DTo(enemy)

        // Use rod when enemy is at optimal distance (4-10 blocks)
        // or when enemy is approaching rapidly
        return distance >= 4.0 && distance <= 12.0
    }

    override fun onStart() {
        rodState = RodState.IDLE
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

    private fun getRodSlot(): Int {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            if (itemStack.item.id == Item.FISHING_ROD) {
                return i
            }
        }
        return -1
    }

    override fun onTick(tick: Tick) {
        val rodSlot = getRodSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        val enemy = getClosestEnemy()

        tick.finishIf("No Rod Found", rodSlot == -1)
        tick.finishIf("No Enemy", enemy == null)

        enemy ?: return

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen !is ContainerScreen) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        tick.prerequisite("In Hotbar", rodSlot <= 8) { moveItemToHotbar(rodSlot, inventory) }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(rodSlot)) {
            selectHotbarSlot(resolveHotbarSlot(rodSlot))
        }

        tick.finishIf("Not Holding Rod", inventory.heldItemStack?.item?.id != Item.FISHING_ROD)

        when (rodState) {
            RodState.IDLE -> {
                // Aim at enemy with prediction
                val predictedX = enemy.x + (enemy.x - enemy.lastX) * 2
                val predictedZ = enemy.z + (enemy.z - enemy.lastZ) * 2

                val dx = predictedX - fakePlayer.x
                val dz = predictedZ - fakePlayer.z
                val targetY =
                        if (enemy.isOnGround)
                                enemy.y + 0.55
                        else enemy.y + enemy.eyeHeight * 0.75
                val dy = targetY - (fakePlayer.y + fakePlayer.eyeHeight)

                val horizDist = sqrt(dx * dx + dz * dz)
                val yaw =
                        Math.toDegrees(-fastArcTan(dx / dz)).toFloat().let {
                            when {
                                dz < 0 && dx < 0 ->
                                        (90 + Math.toDegrees(fastArcTan(dz / dx))).toFloat()
                                dz < 0 && dx > 0 ->
                                        (-90 + Math.toDegrees(fastArcTan(dz / dx))).toFloat()
                                else -> it
                            }
                        }

                // Keep rod aim lower for grounded targets, but a bit higher if enemy is airborne.
                val basePitch = Math.toDegrees(-fastArcTan(dy / horizDist)).toFloat()
                val downwardBias =
                        ((horizDist / 10.0).coerceIn(4.0, 12.0) - if (enemy.isOnGround) 0.0 else 1.5)
                                .toFloat()
                val pitch = (basePitch + downwardBias).coerceIn(-25f, 30f)

                setMouseYaw(yaw)
                setMousePitch(pitch)

                tick.execute { rodState = RodState.THROWING }
            }
            RodState.THROWING -> {
                tick.execute {
                    pressButton(25, MouseButton.Type.RIGHT_CLICK)
                    rodState = RodState.IN_FLIGHT
                }
            }
            RodState.IN_FLIGHT -> {
                // Wait for hook to travel
                tick.execute {
                    if (tickCount > 15) { // About 750ms flight time
                        rodState = RodState.REELING
                    }
                }
            }
            RodState.REELING -> {
                tick.execute {
                    pressButton(25, MouseButton.Type.RIGHT_CLICK) // Reel in
                    lastRodTick = clientInstance.currentTick
                    finish()
                }
            }
        }
    }

    override fun onEnd() {
        rodState = RodState.IDLE
    }

    override fun onEvent(event: Event): Boolean {
        return false
    }

    override fun onGameLoop() {}
}
