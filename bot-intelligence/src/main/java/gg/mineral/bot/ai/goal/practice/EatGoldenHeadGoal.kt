package gg.mineral.bot.ai.goal.practice

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

/**
 * Goal for eating golden apples in UHC/BuildUHC modes. Prioritizes golden heads (durability 1) when
 * critically low health.
 */
class EatGoldenHeadGoal(clientInstance: ClientInstance) :
        InventoryGoal(clientInstance), Sporadic, Timebound {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 100
    private var eating = false
    private val perception = CombatPerception(clientInstance)

    override fun shouldExecute(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        // Check for golden head (golden apple with durability 1)
        var hasGoldenHead = false
        for (i in 0..35) {
            val item = inventory.getItemStackAt(i) ?: continue
            if (item.item.id == Item.GOLDEN_APPLE && item.durability == 1) {
                hasGoldenHead = true
                break
            }
        }

        val enemy = perception.nearestEnemy()
        val hasWindow = enemy == null ||
                enemy.distance3D > 3.0 ||
                fakePlayer.health <= 3.0f ||
                !enemy.pressuringSelf

        // Eat golden head when health is critically low (6 or less)
        return hasGoldenHead && fakePlayer.health <= 6 && hasWindow
    }

    override fun onStart() {
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
    }

    private fun angleAwayFromEnemies(): Float {
        return perception.safeYawAwayFromNearestEnemy()
    }

    private fun distanceAwayFromEnemies(): Double {
        return perception.distanceToNearestEnemy()
    }

    private fun getGoldenHeadSlot(): Int {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            // Golden head is golden apple with durability 1
            if (itemStack.item.id == Item.GOLDEN_APPLE && itemStack.durability == 1) {
                return i
            }
        }
        return -1
    }

    override fun onTick(tick: Tick) {
        val headSlot = getGoldenHeadSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        tick.finishIf("No Golden Head Found", headSlot == -1)
        tick.finishIf("Health Recovered", fakePlayer.health > 10)

        tick.prerequisite("In Hotbar", isItemReadyInHotbar(headSlot, inventory)) {
            moveItemToHotbar(headSlot, inventory)
        }

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(headSlot)) {
            selectHotbarSlot(resolveHotbarSlot(headSlot))
        }

        tick.finishIf(
                "Not Holding Golden Head",
                inventory.heldItemStack?.let {
                    it.item.id == Item.GOLDEN_APPLE && it.durability == 1
                } == false
        )

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
    override fun onEnd() {
        eating = false
        unpressButton(MouseButton.Type.RIGHT_CLICK)
        unpressKey(Key.Type.KEY_SPACE)
    }

    override fun onEvent(event: Event): Boolean {
        if (event is MouseButtonEvent) {
            if (eating && event.type == MouseButton.Type.RIGHT_CLICK && !event.pressed) {
                return true
            } else if (event.type == MouseButton.Type.LEFT_CLICK && event.pressed) {
                return true
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
