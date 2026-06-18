package gg.mineral.bot.ai.goal

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
import gg.mineral.bot.api.screen.type.ContainerScreen

class EatEnchantedGappleGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 100
    private var eating = false
    private var lastEatTick: Int = -600
    private val perception = CombatPerception(clientInstance)

    override fun shouldExecute(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val enemy = perception.nearestVisibleEnemy()
        val hasWindow = enemy != null && (enemy.distance3D >= 3.6 || fakePlayer.health <= 10.0f || !enemy.pressuringSelf)
        val shouldExecute = hasWindow && hasEnchantedGapple() && canEatNow()
        logger.debug("Checking shouldExecute: $shouldExecute")
        return shouldExecute
    }

    override fun onStart() {
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
    }

    private fun canEatNow(): Boolean {
        return clientInstance.currentTick - lastEatTick >= 600
    }

    private fun hasEnchantedGapple(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        return inventory.contains { isEnchantedGapple(it) }
    }

    private fun angleAwayFromEnemies(): Float {
        return perception.safeYawAwayFromNearestEnemy()
    }

    private fun distanceAwayFromEnemies(): Double {
        return perception.distanceToNearestEnemy()
    }

    private fun isEnchantedGapple(itemStack: ItemStack): Boolean {
        return itemStack.item.id == Item.GOLDEN_APPLE && itemStack.durability == 1
    }

    private fun getEnchantedGappleSlot(): Int {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            if (isEnchantedGapple(itemStack)) return i
        }

        return -1
    }

    override fun onTick(tick: Tick) {
        val gappleSlot = getEnchantedGappleSlot()
        val inventory = clientInstance.fakePlayer.inventory

        tick.finishIf("No enchanted golden apple found", gappleSlot == -1)

        tick.prerequisite("In Hotbar", gappleSlot <= 8) {
            moveItemToHotbar(gappleSlot, inventory)
        }

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(gappleSlot)) {
            selectHotbarSlot(resolveHotbarSlot(gappleSlot))
        }

        tick.finishIf("Not holding enchanted golden apple", inventory.heldItemStack?.let { isEnchantedGapple(it) } == false)

        tick.finishIf("30-second cooldown not ready", !canEatNow())

        tick.prerequisite("Eating", eating && getButton(MouseButton.Type.RIGHT_CLICK).isPressed) {
            pressButton(MouseButton.Type.RIGHT_CLICK)
            eating = true
        }

        tick.execute {
            if (distanceAwayFromEnemies() < 16) {
                setMouseYaw(angleAwayFromEnemies())
                pressKey(Key.Type.KEY_SPACE, Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
            }
        }
    }

    override fun blocksContinuousAim(): Boolean = true

    override fun blocksContinuousAttack(): Boolean = true

    override fun blocksContinuousMovement(): Boolean = true

    override fun debugSummary(): String {
        val heldItem = clientInstance.fakePlayer.inventory.heldItemStack?.let { "${it.item.id}:${it.durability}x${it.count}" } ?: "empty"
        return "eating=$eating,lastEatAgo=${clientInstance.currentTick - lastEatTick},canEatNow=${canEatNow()},distance=${distanceAwayFromEnemies()},held=$heldItem"
    }

    override fun onEnd() {
        if (eating) {
            lastEatTick = clientInstance.currentTick
        }

        eating = false
        unpressButton(MouseButton.Type.RIGHT_CLICK)
        unpressKey(Key.Type.KEY_SPACE)
    }

    override fun onEvent(event: Event): Boolean {
        if (event is MouseButtonEvent) {
            if (eating && event.type == MouseButton.Type.RIGHT_CLICK && !event.pressed) {
                logger.debug("Ignoring RIGHT_CLICK release event while eating enchanted gapple")
                return true
            } else if (event.type == MouseButton.Type.LEFT_CLICK && event.pressed) {
                logger.debug("Ignoring LEFT_CLICK press event")
                return true
            }
        }
        return false
    }

    public override fun onGameLoop() {
        if (eating && !getButton(MouseButton.Type.RIGHT_CLICK).isPressed) {
            pressButton(MouseButton.Type.RIGHT_CLICK)
        }
    }
}
