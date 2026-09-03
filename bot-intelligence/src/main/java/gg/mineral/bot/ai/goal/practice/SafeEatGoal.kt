package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.event.peripherals.MouseButtonEvent
import gg.mineral.bot.api.goal.GoalDebugState
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.inv.item.ItemStack

/**
 * Anti-combo food defense goal.
 *
 * Problem: When eating food (steak/golden apple), enemy can combo you since you can't attack while
 * holding right click.
 *
 * Solution:
 * - Only eat when far enough from enemies OR
 * - If forced to eat close to enemy, jump + sprint away
 * - Once eating starts, hold the action long enough to finish
 */
class SafeEatGoal(clientInstance: ClientInstance) :
        InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    private companion object {
        const val FIRST_RELAXATION_TICKS = 2 * 20
        const val SECOND_RELAXATION_TICKS = 4 * 20
    }

    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 120

    private var eating = false
    private var eatingStartTick = 0
    private var foodWindowStartTick = -1
    private val perception = CombatPerception(clientInstance)

    override fun shouldExecute(): Boolean {
        val fakePlayer = clientInstance.fakePlayer

        // Need non-enchanted food and hunger isn't full
        if (getFoodSlot() == -1 || fakePlayer.hunger >= 20) {
            foodWindowStartTick = -1
            return false
        }

        if (foodWindowStartTick == -1) {
            foodWindowStartTick = clientInstance.currentTick
        }

        return canEatInWindow()
    }

    override fun onStart() {
        eating = false
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
    }

    private fun distanceAwayFromEnemies(): Double {
        return perception.snapshot().nearestEnemy?.distance3D ?: Double.MAX_VALUE
    }

    private fun foodWindowTicks(): Int {
        return if (foodWindowStartTick == -1) 0 else clientInstance.currentTick - foodWindowStartTick
    }

    private fun canEatInWindow(): Boolean {
        val enemy = perception.nearestVisibleEnemy() ?: return true
        val waitedTicks = foodWindowTicks()

        return enemy.distance3D >= 10.0 ||
                (!enemy.pressuringSelf && !enemy.movingTowardSelf && enemy.distance3D >= 8.0) ||
                (waitedTicks >= FIRST_RELAXATION_TICKS && enemy.distance3D >= 7.0 && !enemy.movingTowardSelf && !enemy.lookingAtSelf) ||
                (waitedTicks >= SECOND_RELAXATION_TICKS && enemy.distance3D >= 6.5 && !enemy.pressuringSelf && !enemy.lookingAtSelf)
    }

    private fun angleAwayFromEnemies(): Float {
        val fakePlayer = clientInstance.fakePlayer
        val enemy = perception.snapshot().nearestEnemy ?: return fakePlayer.yaw
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
            if (Item.Type.FOOD.isType(item.id) && !isEnchantedGapple(itemStack)) {
                return i
            }
        }
        return -1
    }

    private fun isEnchantedGapple(itemStack: ItemStack): Boolean {
        return itemStack.item.id == Item.GOLDEN_APPLE && itemStack.durability == 1
    }

    override fun onTick(tick: Tick) {
        val foodSlot = getFoodSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        tick.finishIf("No Food", foodSlot == -1)
        tick.finishIf("Hunger Full", fakePlayer.hunger >= 20)
        tick.finishIf("Food Window Unsafe", !eating && !canEatInWindow())

        tick.prerequisite("In Hotbar", isItemReadyInHotbar(foodSlot, inventory)) {
            moveItemToHotbar(foodSlot, inventory)
        }

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
        return "eating=$eating,eatingTicks=$eatingTicks,windowTicks=${foodWindowTicks()},canEatWindow=${canEatInWindow()},distance=${distanceAwayFromEnemies()},held=$heldItem"
    }

    override fun onEnd() {
        eating = false
        if (getFoodSlot() == -1 || clientInstance.fakePlayer.hunger >= 20) {
            foodWindowStartTick = -1
        }
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

        return false
    }

    override fun onGameLoop() {
        if (eating && !getButton(MouseButton.Type.RIGHT_CLICK).isPressed) {
            pressButton(MouseButton.Type.RIGHT_CLICK)
        }
    }
}
