package gg.mineral.bot.ai.goal

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.goal.type.PotionThrowGate
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.GoalDebugState
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.inv.item.ItemStack

/** Turn away, run with a level/slightly lowered view, and issue one use click. */
class ThrowHealthPotGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    override val maxDuration: Long = 45
    override var startTime: Long = 0
    override var executing = false

    private enum class PotState { PREPARING, AIMING, THROWING, RECOVERING }
    private var currentState = PotState.PREPARING
    private val perception = CombatPerception(clientInstance)
    private var lastPotTick = -40
    private var plannedYaw = 0f
    private val plannedPitch = 8f // Positive Minecraft pitch looks down; never search for an upward arc.
    private var throwGate = PotionThrowGate()
    private var throwTick = -1
    private var countBeforeThrow = 0
    private var healthBeforeThrow = 0f

    override fun shouldExecute(): Boolean {
        if (clientInstance.currentTick - lastPotTick < 40 || getHealthPotSlot() == -1) return false
        val health = clientInstance.fakePlayer.health
        val enemy = perception.nearestEnemy()
        return health < 12 && (health < 6 || enemy == null || enemy.distance2D > 3.8 || !enemy.pressuringSelf)
    }

    override fun onStart() {
        clientInstance.mouse.clearPendingClicks()
        clientInstance.keyboard.stopAll()
        clientInstance.fakePlayer.stopUsingItem()
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_Q, Key.Type.KEY_SPACE, Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
        currentState = PotState.PREPARING
        throwGate = PotionThrowGate()
        throwTick = -1
        healthBeforeThrow = clientInstance.fakePlayer.health
    }

    override fun onTick(tick: Tick) {
        val player = clientInstance.fakePlayer
        val inventory = player.inventory
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D, Key.Type.KEY_SPACE)

        when (currentState) {
            PotState.PREPARING -> {
                val slot = getHealthPotSlot()
                tick.finishIf("No health potion", slot == -1 || player.health >= 12)
                tick.prerequisite("Potion in hotbar", isItemReadyInHotbar(slot, inventory)) {
                    moveItemToHotbar(slot, inventory)
                }
                tick.prerequisite("Inventory closed", clientInstance.currentScreen == null) {
                    pressKey(10, Key.Type.KEY_ESCAPE)
                }
                tick.prerequisite("Potion selected", inventory.heldSlot == resolveHotbarSlot(slot)) {
                    selectHotbarSlot(resolveHotbarSlot(slot))
                }
                tick.execute {
                    if (inventory.heldItemStack?.let(::isHealthPot) == true) {
                        plannedYaw = perception.safeYawAwayFromNearestEnemy()
                        currentState = PotState.AIMING
                    }
                }
            }
            PotState.AIMING -> {
                if (!readyToThrow() || player.health >= 12) { finish(); return }
                setMouseYaw(plannedYaw)
                setMousePitch(plannedPitch)
                tick.execute {
                    throwGate.arm(clientInstance.currentTick, plannedYaw, plannedPitch)
                    currentState = PotState.THROWING
                }
            }
            PotState.THROWING -> {
                setMouseYaw(plannedYaw)
                setMousePitch(plannedPitch)
                if (!readyToThrow()) { finish(); return }
                if (throwGate.tryIssue(clientInstance.currentTick, player.yaw, player.pitch)) {
                    countBeforeThrow = countHealthPots()
                    healthBeforeThrow = player.health
                    throwTick = clientInstance.currentTick
                    lastPotTick = throwTick
                    pressButton(5, MouseButton.Type.RIGHT_CLICK)
                    currentState = PotState.RECOVERING
                }
            }
            PotState.RECOVERING -> {
                // Keep running into the splash, without projectile simulation or async work.
                val elapsed = clientInstance.currentTick - throwTick
                if (elapsed <= 1) {
                    setMouseYaw(plannedYaw)
                    setMousePitch(plannedPitch)
                    return
                }
                unpressButton(MouseButton.Type.RIGHT_CLICK)
                setMouseYaw(plannedYaw)
                if (player.health > healthBeforeThrow || elapsed >= 12) {
                    // A click may be ignored while blocking or during a server correction.
                    // Never replay it against a different held item.
                    if (countHealthPots() >= countBeforeThrow) lastPotTick = clientInstance.currentTick
                    if (restoreMelee()) finish()
                }
            }
        }
    }

    private fun readyToThrow(): Boolean = clientInstance.currentScreen == null &&
        !clientInstance.hasPendingInventoryTransaction &&
        clientInstance.fakePlayer.inventory.heldItemStack?.let(::isHealthPot) == true

    private fun restoreMelee(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        val slot = findBestMeleeWeaponSlot(36) { inventory.getItemStackAt(it)?.attackDamage } ?: return true
        if (!isItemReadyInHotbar(slot, inventory)) { moveItemToHotbar(slot, inventory); return false }
        if (clientInstance.currentScreen != null) { pressKey(10, Key.Type.KEY_ESCAPE); return false }
        if (inventory.heldSlot != slot) { selectHotbarSlot(slot); return false }
        return true
    }

    private fun isHealthPot(stack: ItemStack) = stack.item.id == Item.POTION && stack.durability == 16421
    private fun getHealthPotSlot() = (0..35).firstOrNull {
        clientInstance.fakePlayer.inventory.getItemStackAt(it)?.let(::isHealthPot) == true
    } ?: -1
    private fun countHealthPots() = (0..35).sumOf {
        clientInstance.fakePlayer.inventory.getItemStackAt(it)?.takeIf(::isHealthPot)?.count ?: 0
    }

    override fun blocksContinuousAim() = true
    override fun blocksContinuousAttack() = true
    override fun blocksContinuousMovement() = true
    override fun debugSummary() = "state=$currentState,lastPotAgo=${clientInstance.currentTick - lastPotTick},throwTick=$throwTick"
    override fun onEnd() {
        clientInstance.mouse.clearPendingClicks()
        clientInstance.fakePlayer.stopUsingItem()
        unpressKey(Key.Type.KEY_Q, Key.Type.KEY_SPACE, Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
        if (clientInstance.currentScreen != null) pressKey(10, Key.Type.KEY_ESCAPE)
    }
    override fun onEvent(event: Event) = false
    override fun onGameLoop() {}
}
