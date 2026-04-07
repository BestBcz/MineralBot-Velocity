package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.living.ClientLivingEntity
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.event.entity.EntityHurtEvent
import gg.mineral.bot.api.event.peripherals.MouseButtonEvent
import gg.mineral.bot.api.goal.GoalDebugState
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item

/**
 * Anti-combo food defense goal.
 *
 * Problem: When eating food (steak/golden apple), enemy can combo you since you can't attack while
 * holding right click.
 *
 * Solution:
 * - Only eat when far enough from enemies OR
 * - If forced to eat close to enemy, jump + sprint away
 * - Cancel eating to block/attack if combo detected
 */
class SafeEatGoal(clientInstance: ClientInstance) :
        InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 120

    private var eating = false
    private var eatingStartTick = 0
    private var comboDetected = false
    private var hitsTaken = 0
    private var lastEatTick = 0

    override fun shouldExecute(): Boolean {
        if (clientInstance.currentTick - lastEatTick < 30) return false

        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        // Need food and hunger isn't full
        if (!inventory.contains(Item.Type.FOOD)) return false
        if (fakePlayer.hunger >= 19) return false

        // Only eat if safe or really need it
        val distance = distanceAwayFromEnemies()
        val healthCritical = fakePlayer.health < 8

        return (distance > 8.0 && fakePlayer.health > 10) || healthCritical
    }

    override fun onStart() {
        eating = false
        comboDetected = false
        hitsTaken = 0
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
    }

    private fun distanceAwayFromEnemies(): Double {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world

        return world.entities.minOfOrNull {
            if (it is ClientLivingEntity &&
                            !clientInstance.configuration.friendlyUUIDs.contains(it.uuid)
            )
                    it.distance3DTo(fakePlayer)
            else Double.MAX_VALUE
        }
                ?: Double.MAX_VALUE
    }

    private fun angleAwayFromEnemies(): Float {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world

        val enemy =
                world.entities.minByOrNull {
                    if (it is ClientLivingEntity &&
                                    !clientInstance.configuration.friendlyUUIDs.contains(it.uuid)
                    )
                            it.distance3DTo(fakePlayer)
                    else Double.MAX_VALUE
                }
                        ?: return fakePlayer.yaw
        val x: Double = enemy.x - fakePlayer.x
        val z: Double = enemy.z - fakePlayer.z

        var yaw = Math.toDegrees(-fastArcTan(x / z)).toFloat()
        if (z < 0.0 && x < 0.0) yaw = (90.0 + Math.toDegrees(fastArcTan(z / x))).toFloat()
        else if (z < 0.0 && x > 0.0) yaw = (-90.0 + Math.toDegrees(fastArcTan(z / x))).toFloat()
        return yaw + 180.0f
    }

    private fun getFoodSlot(): Int {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            val item = itemStack.item
            if (Item.Type.FOOD.isType(item.id)) {
                return i
            }
        }
        return -1
    }

    override fun onTick(tick: Tick) {
        val foodSlot = getFoodSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        tick.finishIf("No Food", foodSlot == -1)
        tick.finishIf("Hunger Full", fakePlayer.hunger >= 19)

        // Cancel if combo detected and we've been hit too much
        if (comboDetected && hitsTaken >= 2) {
            tick.execute {
                unpressButton(MouseButton.Type.RIGHT_CLICK)
                eating = false
                finish() // Stop eating, need to fight back
            }
            return
        }

        tick.prerequisite("In Hotbar", foodSlot <= 8) { moveItemToHotbar(foodSlot, inventory) }

        tick.prerequisite("Screen Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(foodSlot)) {
            selectHotbarSlot(resolveHotbarSlot(foodSlot))
        }

        tick.finishIf(
                "Not Holding Food",
                inventory.heldItemStack?.let { Item.Type.FOOD.isType(it.item.id) } == false
        )

        tick.prerequisite("Eating", eating && getButton(MouseButton.Type.RIGHT_CLICK).isPressed) {
            pressButton(MouseButton.Type.RIGHT_CLICK)
            eating = true
            eatingStartTick = clientInstance.currentTick
        }

        tick.execute {
            val distance = distanceAwayFromEnemies()

            // Run away while eating
            if (distance < 10) {
                setMouseYaw(angleAwayFromEnemies())
                pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)

                // Jump to avoid getting hit
                if (fakePlayer.isOnGround && distance < 5) {
                    // Space + S jump-reset creates spacing while still finishing the eat.
                    pressKey(100, Key.Type.KEY_SPACE, Key.Type.KEY_S)
                }
            }
        }
    }

    override fun blocksContinuousAim(): Boolean = true

    override fun blocksContinuousAttack(): Boolean = true

    override fun blocksContinuousMovement(): Boolean = true

    override fun debugSummary(): String {
        val heldItem = clientInstance.fakePlayer.inventory.heldItemStack?.let { "${it.item.id}:${it.durability}x${it.count}" } ?: "empty"
        val eatingTicks = if (eating) clientInstance.currentTick - eatingStartTick else 0
        return "eating=$eating,eatingTicks=$eatingTicks,hitsTaken=$hitsTaken,comboDetected=$comboDetected,lastEatAgo=${clientInstance.currentTick - lastEatTick},distance=${distanceAwayFromEnemies()},held=$heldItem"
    }

    override fun onEnd() {
        eating = false
        comboDetected = false
        hitsTaken = 0
        lastEatTick = clientInstance.currentTick
        if (clientInstance.currentScreen != null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }
        unpressButton(MouseButton.Type.RIGHT_CLICK)
        unpressKey(Key.Type.KEY_SPACE)
    }

    override fun onEvent(event: Event): Boolean {
        if (event is MouseButtonEvent) {
            if (eating && event.type == MouseButton.Type.RIGHT_CLICK && !event.pressed) {
                return true // Prevent releasing right click while eating
            }
        }

        if (event is EntityHurtEvent && eating) {
            val fakePlayer = clientInstance.fakePlayer
            if (event.attackedEntity.uuid == fakePlayer.uuid) {
                hitsTaken++
                if (hitsTaken >= 2) {
                    comboDetected = true
                }
            }
        }

        return false
    }

    override fun onGameLoop() {
        if (eating && !getButton(MouseButton.Type.RIGHT_CLICK).isPressed) {
            pressButton(MouseButton.Type.RIGHT_CLICK)
        }
    }
}
