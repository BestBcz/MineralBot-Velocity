package gg.mineral.bot.ai.goal

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.event.peripherals.MouseButtonEvent
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.screen.type.ContainerScreen

class EatFoodGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 100
    private var eating = false
    private var foodWindowStartTick = -1
    private val perception = CombatPerception(clientInstance)

    override fun shouldExecute(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        if (!hasFood() || fakePlayer.hunger >= 20.0f) {
            foodWindowStartTick = -1
            logger.debug("Checking shouldExecute: false")
            return false
        }

        if (foodWindowStartTick == -1) {
            foodWindowStartTick = clientInstance.currentTick
        }

        val shouldExecute = canEatInWindow()
        logger.debug("Checking shouldExecute: $shouldExecute")
        return shouldExecute
    }

    override fun onStart() {
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
    }

    init {
        logger.debug("EatFoodGoal initialized")
    }

    private fun hasFood(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        val hasFood = inventory.contains(Item.Type.FOOD)
        logger.debug("Has food: $hasFood")
        return hasFood
    }

    private fun canEatInWindow(): Boolean {
        val enemy = perception.nearestEnemy() ?: return true
        val waitedTicks = if (foodWindowStartTick == -1) 0 else clientInstance.currentTick - foodWindowStartTick

        if (!enemy.lineOfSightLikelyClear) return true
        if (enemy.distance3D >= 6.5) return true
        if (!enemy.pressuringSelf && enemy.distance3D >= 3.7) return true
        if (waitedTicks >= 50 && enemy.distance3D >= 3.2 && !enemy.movingTowardSelf) return true
        if (waitedTicks >= 100 && enemy.distance3D >= 2.8 && !enemy.lookingAtSelf) return true
        return waitedTicks >= 160 && enemy.distance3D >= 2.6 && clientInstance.fakePlayer.health >= 12.0f
    }

    private fun angleAwayFromEnemies(): Float {
        return perception.safeYawAwayFromNearestEnemy()
    }

    private fun distanceAwayFromEnemies(): Double {
        return perception.distanceToNearestEnemy()
    }

    private fun getFoodSlot(): Int {
        var foodSlot = -1
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            val item = itemStack.item
            if (Item.Type.FOOD.isType(item.id)) {
                foodSlot = i
                break
            }
        }

        return foodSlot
    }

    override fun onTick(tick: Tick) {
        val foodSlot = getFoodSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        tick.finishIf("Valid Food Found", foodSlot == -1)

        tick.finishIf("Hunger Satisfied", fakePlayer.hunger >= 20)
        tick.finishIf("Food Window Unsafe", !eating && !canEatInWindow())

        tick.prerequisite("In Hotbar", foodSlot <= 8) {
            moveItemToHotbar(foodSlot, inventory)
        }

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(
                10,
                Key.Type.KEY_ESCAPE
            )
        }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(foodSlot)) {
            selectHotbarSlot(resolveHotbarSlot(foodSlot))
        }

        tick.finishIf(
            "Not Holding Valid Food",
            inventory.heldItemStack?.let { Item.Type.FOOD.isType(it.item.id) } == false)

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

    override fun onEnd() {
        eating = false
        if (clientInstance.fakePlayer.hunger >= 20.0f) {
            foodWindowStartTick = -1
        }
        unpressButton(MouseButton.Type.RIGHT_CLICK)
        unpressKey(Key.Type.KEY_SPACE)
    }

    override fun onEvent(event: Event): Boolean {
        if (event is MouseButtonEvent) {
            if (eating && event.type == MouseButton.Type.RIGHT_CLICK && !event.pressed) {
                logger.debug("Ignoring RIGHT_CLICK release event while eating")
                return true
            }
        }
        return false
    }

    public override fun onGameLoop() {
    }
}
