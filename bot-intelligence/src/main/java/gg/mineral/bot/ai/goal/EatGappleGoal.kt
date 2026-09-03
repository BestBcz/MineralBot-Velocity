package gg.mineral.bot.ai.goal

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.goal.type.ConsumableUseSession
import gg.mineral.bot.ai.goal.type.isConsumableReentryReady
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

class EatGappleGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    private companion object {
        const val REENTRY_GRACE_TICKS = 10
    }

    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 100
    private var eating = false
    private var eatCompleted = false
    private var eatSession: ConsumableUseSession? = null
    private var lastCompletedEatTick = -REENTRY_GRACE_TICKS
    private val perception = CombatPerception(clientInstance)

    override fun shouldExecute(): Boolean {
        if (!isConsumableReentryReady(
                        clientInstance.currentTick,
                        lastCompletedEatTick,
                        REENTRY_GRACE_TICKS
                )
        ) return false

        var hasRegen = false
        val regenId = PotionEffectType.REGENERATION.id
        val fakePlayer = clientInstance.fakePlayer
        val activeIds = fakePlayer.activePotionEffectIds

        for (activeId in activeIds) if (activeId == regenId) {
            hasRegen = true
            break
        }

        val enemy = perception.nearestVisibleEnemy()
        val distance = enemy?.distance3D ?: Double.MAX_VALUE
        val emergency = fakePlayer.health <= 5.0f
        val hasEatWindow = enemy != null && (distance >= 4.0 || emergency || !enemy.pressuringSelf)
        val shouldExecute = hasEatWindow &&
            hasGapple() &&
            !hasRegen &&
            (fakePlayer.health < 10 || distance in 8.0..16.0 || (enemy?.heldAttackDamage ?: 0.0) >= 6.0 && distance < 7.0)
        logger.debug("Checking shouldExecute: $shouldExecute")
        return shouldExecute
    }

    override fun onStart() {
        eating = false
        eatCompleted = false
        eatSession = null
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
    }

    init {
        logger.debug("EatGappleGoal initialized")
    }

    private fun hasGapple(): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        val hasGapple = inventory.contains(Item.GOLDEN_APPLE)
        logger.debug("Has golden apple: $hasGapple")
        return hasGapple
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

    private fun getGappleSlot(): Int {
        var gappleSlot = -1
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            val item = itemStack.item
            if (item.id == Item.GOLDEN_APPLE) {
                gappleSlot = i
                break
            }
        }

        return gappleSlot
    }

    private fun countGapples(): Int {
        val inventory = clientInstance.fakePlayer.inventory
        var count = 0
        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            if (itemStack.item.id == Item.GOLDEN_APPLE) count += itemStack.count
        }
        return count
    }

    override fun onTick(tick: Tick) {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        val session = eatSession

        if (eating &&
                session != null &&
                session.isComplete(
                        currentTick = clientInstance.currentTick,
                        currentItemCount = countGapples(),
                        isUsingItem = fakePlayer.isEatingOrDrinking
                )
        ) {
            eatCompleted = true
            tick.finishIf("Golden apple consumed", true)
            return
        }

        val gappleSlot = getGappleSlot()

        tick.finishIf("Valid gapple slot not found", gappleSlot == -1)

        tick.prerequisite("In Hotbar", isItemReadyInHotbar(gappleSlot, inventory)) {
            moveItemToHotbar(gappleSlot, inventory)
        }

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(gappleSlot)) {
            selectHotbarSlot(resolveHotbarSlot(gappleSlot))
        }

        tick.finishIf("Not Holding Valid Gapple", inventory.heldItemStack?.item?.id != Item.GOLDEN_APPLE)

        tick.finishIf(
                "Has Regen",
                !eating &&
                        fakePlayer.activePotionEffectIds.any {
                            it == PotionEffectType.REGENERATION.id
                        }
        )

        tick.prerequisite("Eating", eating && getButton(MouseButton.Type.RIGHT_CLICK).isPressed) {
            pressButton(MouseButton.Type.RIGHT_CLICK)
            eating = true
            eatSession =
                    ConsumableUseSession(
                            startedTick = clientInstance.currentTick,
                            initialItemCount = countGapples()
                    )
        }

        tick.execute {
            pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
            unpressKey(Key.Type.KEY_S, Key.Type.KEY_A, Key.Type.KEY_D)
            if (distanceAwayFromEnemies() < 16) {
                setMouseYaw(angleAwayFromEnemies())
                pressKey(Key.Type.KEY_SPACE)
            } else {
                unpressKey(Key.Type.KEY_SPACE)
            }
        }
    }

    override fun blocksContinuousAim(): Boolean = true

    override fun blocksContinuousAttack(): Boolean = true

    override fun blocksContinuousMovement(): Boolean = true

    override fun debugSummary(): String {
        val hasRegen = clientInstance.fakePlayer.activePotionEffectIds.any { it == PotionEffectType.REGENERATION.id }
        val heldItem = clientInstance.fakePlayer.inventory.heldItemStack?.let { "${it.item.id}:${it.durability}x${it.count}" } ?: "empty"
        val eatElapsed = eatSession?.let { clientInstance.currentTick - it.startedTick } ?: -1
        return "eating=$eating,eatElapsed=$eatElapsed,hasRegen=$hasRegen,distance=${distanceAwayFromEnemies()},held=$heldItem"
    }

    override fun onEnd() {
        if (eating) {
            clientInstance.fakePlayer.stopUsingItem()
        }
        if (eatCompleted) lastCompletedEatTick = clientInstance.currentTick
        eating = false
        eatCompleted = false
        eatSession = null
        unpressButton(MouseButton.Type.RIGHT_CLICK)
        unpressKey(Key.Type.KEY_SPACE)
    }

    override fun onEvent(event: Event): Boolean {
        if (event is MouseButtonEvent) {
            if (eating && event.type == MouseButton.Type.RIGHT_CLICK && !event.pressed) {
                logger.debug("Ignoring RIGHT_CLICK release event while eating")
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
