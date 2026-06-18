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
import gg.mineral.bot.api.inv.potion.Potion
import gg.mineral.bot.api.screen.type.ContainerScreen

class DrinkPotionGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    private companion object {
        const val REFRESH_WINDOW_TICKS = 30 * 20
    }

    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 100
    private var drinking = false
    private var drinkWindowStartTick = -1
    private val perception = CombatPerception(clientInstance)

    override fun shouldExecute(): Boolean {
        val hasPotion = hasDrinkablePotion()
        if (!hasPotion) {
            drinkWindowStartTick = -1
            logger.debug("Checking shouldExecute: false")
            return false
        }

        if (drinkWindowStartTick == -1) {
            drinkWindowStartTick = clientInstance.currentTick
        }

        val shouldExecute = canDrinkInWindow()
        logger.debug("Checking shouldExecute: $shouldExecute")
        return shouldExecute
    }

    override fun onStart() {
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
    }

    init {
        logger.debug("DrinkPotionGoal initialized")
    }

    private fun hasDrinkablePotion(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        val hasDrinkablePotion = inventory.containsPotion {
            isValidPotion(it)
        }
        logger.debug("Has drinkable potion: $hasDrinkablePotion")
        return hasDrinkablePotion
    }

    private fun canDrinkInWindow(): Boolean {
        val enemy = perception.nearestVisibleEnemy() ?: return true
        val waitedTicks = if (drinkWindowStartTick == -1) 0 else clientInstance.currentTick - drinkWindowStartTick

        return enemy.distance3D >= 6.0 ||
            (!enemy.pressuringSelf && enemy.distance3D >= 3.8) ||
            (waitedTicks >= 60 && enemy.distance3D >= 3.4 && !enemy.movingTowardSelf) ||
            (waitedTicks >= 120 && enemy.distance3D >= 3.0 && !enemy.lookingAtSelf)
    }

    private fun canSeeEnemy(): Boolean {
        val canSeeEnemy = perception.canSeeEnemy()
        logger.debug("Checking canSeeEnemy: $canSeeEnemy")
        return canSeeEnemy
    }

    private fun angleAwayFromEnemies(): Float {
        return perception.safeYawAwayFromNearestEnemy()
    }

    private fun distanceAwayFromEnemies(): Double {
        return perception.distanceToNearestEnemy()
    }

    private fun isValidPotion(potion: Potion): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        // TODO: exclude negative potions
        return !potion.isSplash &&
            potion.effects.isNotEmpty() &&
            potion.effects.any { effect ->
                val remainingTicks = fakePlayer.clientActivePotionEffects
                    .firstOrNull { it.potionID == effect.potionID }
                    ?.duration
                remainingTicks == null || remainingTicks <= REFRESH_WINDOW_TICKS
            }
    }

    private fun isValidPotion(itemStack: ItemStack): Boolean {
        if (itemStack.item.id != Item.POTION) return false
        val potion = itemStack.potion ?: return false
        return isValidPotion(potion)
    }

    private fun getPotionSlot(): Int {
        var potionSlot = -1
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        // Look for a non-splash potion in one of the 36 slots
        invLoop@ for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            if (isValidPotion(itemStack)) {
                potionSlot = i
                break
            }
        }
        return potionSlot
    }

    override fun onTick(tick: Tick) {
        val potionSlot = getPotionSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        tick.finishIf("No Valid Potion Found", potionSlot == -1)

        tick.prerequisite("In Hotbar", potionSlot <= 8) {
            moveItemToHotbar(potionSlot, inventory)
        }

        tick.prerequisite(
            "Inventory Closed",
            clientInstance.currentScreen == null
        ) { pressKey(10, Key.Type.KEY_ESCAPE) }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(potionSlot)) {
            selectHotbarSlot(resolveHotbarSlot(potionSlot))
        }

        tick.finishIf("Not Holding Valid Potion", inventory.heldItemStack?.let { isValidPotion(it) } == false)

        tick.finishIf("Potion Not Needed", !drinking && !shouldExecute())

        tick.prerequisite("Drinking", drinking && getButton(MouseButton.Type.RIGHT_CLICK).isPressed) {
            pressButton(MouseButton.Type.RIGHT_CLICK)
            drinking = true
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
        return "drinking=$drinking,canSeeEnemy=${canSeeEnemy()},hasDrinkablePotion=${hasDrinkablePotion()},windowTicks=${clientInstance.currentTick - drinkWindowStartTick},held=$heldItem"
    }

    override fun onEnd() {
        drinking = false
        drinkWindowStartTick = -1
        unpressButton(MouseButton.Type.RIGHT_CLICK)
        unpressKey(Key.Type.KEY_SPACE)
    }

    override fun onEvent(event: Event): Boolean {
        if (event is MouseButtonEvent) {
            if (drinking && event.type == MouseButton.Type.RIGHT_CLICK && !event.pressed) {
                logger.debug("Ignoring RIGHT_CLICK release event while drinking")
                return true
            }
        }
        return false
    }

    public override fun onGameLoop() {
        if (drinking && !getButton(MouseButton.Type.RIGHT_CLICK).isPressed) {
            pressButton(MouseButton.Type.RIGHT_CLICK)
        }
    }
}
