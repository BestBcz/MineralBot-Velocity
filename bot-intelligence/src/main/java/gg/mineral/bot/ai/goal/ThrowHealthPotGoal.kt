package gg.mineral.bot.ai.goal

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.goal.type.PotionThrowGate
import gg.mineral.bot.ai.goal.type.HealthPotTiming
import gg.mineral.bot.ai.goal.type.HealthPotBurst
import gg.mineral.bot.ai.goal.type.HealthPotPressure
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.event.entity.EntityHurtEvent
import gg.mineral.bot.api.event.entity.EntityHealthUpdateEvent
import gg.mineral.bot.api.goal.GoalDebugState
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.inv.item.ItemStack

/** Keep moving into the forward splash; interrupt preparation only for close counterattacks. */
class ThrowHealthPotGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    override val maxDuration: Long
        get() = if (currentState == PotState.RECOVERING || currentState == PotState.COUNTERATTACKING ||
            currentState == PotState.PRESSURING) Long.MAX_VALUE else 160
    override var startTime: Long = 0
    override var executing = false

    private enum class PotState { PREPARING, AIMING, THROWING, RECOVERING, COUNTERATTACKING, PRESSURING }
    private var currentState = PotState.PREPARING
    private val perception = CombatPerception(clientInstance)
    private var lastPotTick = -40
    private var plannedYaw = 0f
    private val plannedPitch = 8f
    private val timing = HealthPotTiming()
    private var throwGate = PotionThrowGate()
    private var throwTick = -1
    private var burst = HealthPotBurst()
    private var urgent = false
    private var attemptHitVersion = 0
    private var immediateThrow = false

    override fun shouldExecute(): Boolean {
        val health = clientInstance.fakePlayer.health
        timing.observe(clientInstance.currentTick, health)
        if (getHealthPotSlot() == -1) return false
        if (health >= 6f && clientInstance.currentTick - lastPotTick < 40) return false
        return health <= 10f && !shouldPressure(health)
    }

    private fun shouldPressure(health: Float): Boolean = HealthPotPressure.shouldPressure(
        health, perception.bestTarget()?.health, clientInstance.configuration.healthAdvantagePressureEnabled)

    override fun onStart() {
        clientInstance.mouse.clearPendingClicks()
        clientInstance.keyboard.stopAll()
        clientInstance.fakePlayer.stopUsingItem()
        currentState = PotState.PREPARING
        throwGate = PotionThrowGate(clientInstance.configuration.healthPotAimWaitTicks)
        throwTick = -1
        burst = HealthPotBurst()
        urgent = clientInstance.fakePlayer.health < 6f
        if (urgent) burst.requireTwo()
        attemptHitVersion = timing.hitVersion
        immediateThrow = false
        walkForward()
    }

    private fun walkForward() {
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_Q, Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D, Key.Type.KEY_SPACE)
    }

    override fun onTick(tick: Tick) {
        val player = clientInstance.fakePlayer
        val inventory = player.inventory
        if (timing.observe(clientInstance.currentTick, player.health)) burst.healed()
        burst.observeCount(countHealthPots())
        if (player.health < 6f) {
            urgent = true
            burst.requireTwo()
        }
        // Re-evaluate the current target's packet-synchronized health every tick. Once a
        // bottle has been thrown, finish splash recovery and any required burst before attacking.
        if (!urgent && throwTick < 0 && shouldPressure(player.health)) {
            if (currentState != PotState.PRESSURING) {
                clientInstance.mouse.clearPendingClicks()
                clientInstance.keyboard.stopAll()
                player.stopUsingItem()
                currentState = PotState.PRESSURING
            }
            if (restoreMelee()) finish()
            return
        }
        if (currentState == PotState.PRESSURING) {
            clientInstance.mouse.clearPendingClicks()
            clientInstance.keyboard.stopAll()
            player.stopUsingItem()
            prepareNext()
            attemptHitVersion = timing.hitVersion
        }
        if (currentState == PotState.COUNTERATTACKING) {
            val enemy = perception.nearestEnemy()
            if (urgent || enemy == null || enemy.distance2D > 2.5) {
                clientInstance.mouse.clearPendingClicks()
                player.stopUsingItem()
                prepareNext()
                attemptHitVersion = timing.hitVersion
            } else {
                if (player.health > 10f) { if (restoreMelee()) finish() }
                else restoreMelee()
                return
            }
        }
        // Once a bottle is airborne, keep walking into it until healing is confirmed.
        val enemyDistance = perception.nearestEnemy()?.distance2D ?: Double.POSITIVE_INFINITY
        val hitDuringAttempt = timing.hitVersion > attemptHitVersion
        if (throwTick < 0 && hitDuringAttempt && enemyDistance > 2.5 && !immediateThrow) {
            // Do not extend the retreat when a distant attacker interrupts potion preparation.
            immediateThrow = true
            throwGate = PotionThrowGate(0)
            if (currentState == PotState.THROWING) {
                throwGate.arm(clientInstance.currentTick - 1, plannedYaw, plannedPitch)
            }
        }
        if (!urgent && throwTick < 0 && timing.shouldCounterattack(player.health,
                enemyDistance, hitDuringAttempt)) {
            clientInstance.mouse.clearPendingClicks()
            clientInstance.keyboard.stopAll()
            currentState = PotState.COUNTERATTACKING
            restoreMelee()
            return
        }
        if (burst.complete()) currentState = PotState.RECOVERING
        walkForward()

        when (currentState) {
            PotState.PREPARING -> {
                val slot = getHealthPotSlot()
                tick.finishIf("No health potion", slot == -1)
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
                        // Keep the same escape direction for both bottles.
                        if (throwTick < 0) plannedYaw = perception.safeYawAwayFromNearestEnemy()
                        if (immediateThrow) {
                            setMouseYaw(plannedYaw)
                            setMousePitch(plannedPitch)
                            throwGate.arm(clientInstance.currentTick - 1, plannedYaw, plannedPitch)
                            currentState = PotState.THROWING
                            tryThrow()
                        } else currentState = PotState.AIMING
                    }
                }
            }
            PotState.AIMING -> {
                if (!readyToThrow()) { finish(); return }
                setMouseYaw(plannedYaw)
                setMousePitch(plannedPitch)
                throwGate.arm(clientInstance.currentTick, plannedYaw, plannedPitch)
                currentState = PotState.THROWING
            }
            PotState.THROWING -> {
                setMouseYaw(plannedYaw)
                setMousePitch(plannedPitch)
                if (!readyToThrow()) { finish(); return }
                tryThrow()
            }
            PotState.RECOVERING -> {
                setMouseYaw(plannedYaw)
                setMousePitch(plannedPitch)
                val elapsed = clientInstance.currentTick - throwTick
                if (elapsed <= 1) return
                unpressButton(MouseButton.Type.RIGHT_CLICK)
                burst.observeCount(countHealthPots())
                val hasPotion = getHealthPotSlot() != -1
                if (burst.nextReady(clientInstance.currentTick) && hasPotion) {
                    prepareFollowup()
                } else if (burst.complete(noPotionsRemaining = !hasPotion)) {
                    if (restoreMelee()) finish()
                } else if (burst.missed(clientInstance.currentTick)) {
                    // Failed or ignored clicks are retried, never treated as successful healing.
                    if (hasPotion) prepareFollowup()
                    else if (restoreMelee()) finish() // No consumables remain: avoid permanent lock.
                }
            }
            PotState.COUNTERATTACKING, PotState.PRESSURING -> Unit // Handled above; controls belong to melee.
        }
    }

    private fun prepareFollowup() {
        prepareNext(immediate = true)
        // Reuse an already selected potion without extra preparation/aiming ticks.
        if (readyToThrow()) {
            throwGate.arm(clientInstance.currentTick - 1, plannedYaw, plannedPitch)
            currentState = PotState.THROWING
            tryThrow()
        }
    }

    private fun tryThrow() {
        val player = clientInstance.fakePlayer
        if (!readyToThrow()) return
        if (throwGate.tryIssue(clientInstance.currentTick, player.yaw, player.pitch,
                immediateThrow || urgent || timing.settled(clientInstance.currentTick))) {
            throwTick = clientInstance.currentTick
            burst.thrown(throwTick, countHealthPots())
            lastPotTick = throwTick
            pressButton(5, MouseButton.Type.RIGHT_CLICK)
            currentState = PotState.RECOVERING
        }
    }

    private fun prepareNext(immediate: Boolean = false) {
        currentState = PotState.PREPARING
        tickCount = 0
        immediateThrow = immediate
        throwGate = PotionThrowGate(if (immediate) 0 else clientInstance.configuration.healthPotAimWaitTicks)
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

    private fun yieldsToMelee() = currentState == PotState.COUNTERATTACKING || currentState == PotState.PRESSURING
    override fun blocksContinuousAim() = !yieldsToMelee()
    override fun blocksContinuousAttack() = !yieldsToMelee()
    override fun blocksContinuousMovement() = !yieldsToMelee()
    override fun debugSummary() = "state=$currentState,urgent=$urgent,bottles=${burst.consumedBottles}/${burst.requiredBottles},throwTick=$throwTick"
    override fun onEnd() {
        clientInstance.mouse.clearPendingClicks()
        clientInstance.fakePlayer.stopUsingItem()
        unpressKey(Key.Type.KEY_Q, Key.Type.KEY_SPACE, Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
        if (clientInstance.currentScreen != null) pressKey(10, Key.Type.KEY_ESCAPE)
    }
    override fun onEvent(event: Event): Boolean {
        if (event is EntityHurtEvent && event.attackedEntity.entityId == clientInstance.fakePlayer.entityId) {
            timing.hit(clientInstance.currentTick)
        }
        if (event is EntityHealthUpdateEvent && timing.observe(clientInstance.currentTick, event.health)) burst.healed()
        return false
    }
    override fun onGameLoop() {}
}
