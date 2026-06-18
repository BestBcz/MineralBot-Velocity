package gg.mineral.bot.ai.goal

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.effect.PotionEffectType
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

class DrinkStrengthPotionGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 100
    private var drinking = false
    private val perception = CombatPerception(clientInstance)

    override fun shouldExecute(): Boolean {
        val enemy = perception.nearestVisibleEnemy()
        val hasWindow = enemy != null && (enemy.distance3D >= 4.2 || !enemy.pressuringSelf)
        val shouldExecute = hasWindow && hasStrengthPotion() && !hasStrengthEffect()
        logger.debug("Checking shouldExecute: $shouldExecute")
        return shouldExecute
    }

    override fun onStart() {
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
    }

    private fun hasStrengthEffect(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        return fakePlayer.activePotionEffectIds.any { it == PotionEffectType.INCREASE_DAMAGE.id }
    }

    private fun hasStrengthPotion(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        return inventory.containsPotion { isStrengthPotion(it) }
    }

    private fun angleAwayFromEnemies(): Float {
        return perception.safeYawAwayFromNearestEnemy()
    }

    private fun distanceAwayFromEnemies(): Double {
        return perception.distanceToNearestEnemy()
    }

    private fun isStrengthPotion(potion: Potion): Boolean {
        if (potion.isSplash) return false
        return potion.effects.any { it.potionID == PotionEffectType.INCREASE_DAMAGE.id }
    }

    private fun isStrengthPotion(itemStack: ItemStack): Boolean {
        if (itemStack.item.id != Item.POTION) return false
        val potion = itemStack.potion ?: return false
        return isStrengthPotion(potion)
    }

    private fun getStrengthPotionSlot(): Int {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            if (isStrengthPotion(itemStack)) return i
        }

        return -1
    }

    override fun onTick(tick: Tick) {
        val potionSlot = getStrengthPotionSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        tick.finishIf("No strength potion found", potionSlot == -1)

        tick.prerequisite("In Hotbar", potionSlot <= 8) {
            moveItemToHotbar(potionSlot, inventory)
        }

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(potionSlot)) {
            selectHotbarSlot(resolveHotbarSlot(potionSlot))
        }

        tick.finishIf("Not holding strength potion", inventory.heldItemStack?.let { isStrengthPotion(it) } == false)

        tick.finishIf("Strength effect already active", hasStrengthEffect())

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
        return "drinking=$drinking,hasStrengthEffect=${hasStrengthEffect()},hasStrengthPotion=${hasStrengthPotion()},held=$heldItem"
    }

    override fun onEnd() {
        drinking = false
        unpressButton(MouseButton.Type.RIGHT_CLICK)
        unpressKey(Key.Type.KEY_SPACE)
    }

    override fun onEvent(event: Event): Boolean {
        if (event is MouseButtonEvent) {
            if (drinking && event.type == MouseButton.Type.RIGHT_CLICK && !event.pressed) {
                logger.debug("Ignoring RIGHT_CLICK release event while drinking strength")
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
