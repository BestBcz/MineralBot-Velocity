package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.event.peripherals.MouseButtonEvent
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.util.MathUtil
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/** Composition keeps the existing public goal classes/constructors and inventory transaction path. */
internal class SupplyController(
    private val client: ClientInstance,
    private val kind: SupplyKind,
    private val itemReady: (Int) -> Boolean,
    private val moveItem: (Int) -> Unit,
    private val selectSlot: (Int) -> Unit,
    private val finishGoal: () -> Unit
) : MathUtil {
    private enum class Stage { IDLE, PREPARING, STRIKING, AFTER_STRIKE, RETREATING, USING }

    var sharedState = SupplyState()
    private val perception = CombatPerception(client)
    private var stage = Stage.IDLE
    private var item: SupplyItem? = null
    private var session: SupplyUseSession? = null
    private var source = SupplyWindow.UNSAFE
    private var outcome = SupplyOutcome.IN_PROGRESS
    private var exitReason = "none"
    private var strikeTarget: UUID? = null
    private var strikeStartedTick = 0
    private var strikeTick = -1
    private var strikeAttempted = false
    private var issuingStrike = false
    private var retreatStartedTick = -1
    private val tick get() = client.currentTick
    private val reach get() = client.configuration.reach.toDouble()

    fun shouldExecute(): Boolean {
        if (!sharedState.ready(tick) || emergencyHealingNeeded()) return false
        if (SupplyItems.findNeededSlot(client, kind) == -1) return false
        return window() != SupplyWindow.UNSAFE || canRetreat()
    }

    fun onStart() {
        val slot = SupplyItems.findNeededSlot(client, kind)
        item = if (slot >= 0) client.fakePlayer.inventory.getItemStackAt(slot)?.let(SupplyItem::from) else null
        session = null
        outcome = SupplyOutcome.IN_PROGRESS
        exitReason = "none"
        strikeTarget = null
        strikeTick = -1
        strikeAttempted = false
        retreatStartedTick = -1
        stage = Stage.PREPARING
        client.fakePlayer.stopUsingItem()
        client.mouse.clearPendingClicks()
        stopMovement()
    }

    fun onTick(step: Goal.Tick) {
        val lockedItem = item ?: return end(step, "No supply item", SupplyOutcome.INTERRUPTED)
        val inventory = client.fakePlayer.inventory

        // Check the locked item before searching slots: the last consumed item may already be gone.
        if (stage == Stage.USING) {
            val result = useOutcome(lockedItem)
            if (result != SupplyOutcome.IN_PROGRESS) return end(step, result.name, result)
            moveAway()
            return
        }
        if (emergencyHealingNeeded()) return end(step, "Emergency healing", SupplyOutcome.INTERRUPTED)

        val slot = lockedItem.findSlot(inventory)
        val stack = if (slot >= 0) inventory.getItemStackAt(slot) else null
        if (stack == null || !SupplyItems.needed(client, kind, stack))
            return end(step, "Supply no longer needed", SupplyOutcome.INTERRUPTED)

        source = window()
        if (stage == Stage.AFTER_STRIKE) {
            if (tick <= strikeTick) return
            stage = Stage.PREPARING
        }
        if (source == SupplyWindow.UNSAFE) {
            if (stage == Stage.STRIKING || strikeAttempted)
                return end(step, "Window lost after strike", SupplyOutcome.INTERRUPTED)
            if (!canRetreat() && stage != Stage.RETREATING)
                return end(step, "Unsafe supply window", SupplyOutcome.INTERRUPTED)
            if (retreatStartedTick == -1) retreatStartedTick = tick
            stage = Stage.RETREATING
            if (tick - retreatStartedTick >= SupplyPolicy.RETREAT_TICKS)
                return end(step, "Retreat timed out", SupplyOutcome.INTERRUPTED)
            if (!moveAway()) return end(step, "No safe retreat direction", SupplyOutcome.INTERRUPTED)
            return
        }

        if (stage == Stage.RETREATING) stage = Stage.PREPARING
        val nearest = perception.snapshot().enemies.minByOrNull { it.distance2D }
        if (stage == Stage.PREPARING && !strikeAttempted && source == SupplyWindow.OPPONENT_SUPPLY &&
            nearest != null && nearest.distance3D <= reach) {
            stage = Stage.STRIKING
            strikeTarget = nearest.uuid
            strikeStartedTick = tick
        }
        if (stage == Stage.STRIKING) {
            prepareStrike()
            return
        }

        // Inventory preparation can take several ticks; re-evaluate safety before every step.
        moveAway()
        step.prerequisite("Supply in hotbar", itemReady(slot)) { moveItem(slot) }
        step.prerequisite("Supply screen closed", client.currentScreen == null) {
            client.keyboard.pressKey(10, Key.Type.KEY_ESCAPE)
        }
        val hotbarSlot = if (slot in 0..8) slot else 8
        step.prerequisite("Supply slot selected", inventory.heldSlot == hotbarSlot) { selectSlot(hotbarSlot) }
        step.execute {
            if (!lockedItem.matches(inventory.heldItemStack)) {
                end(step, "Supply held item changed", SupplyOutcome.INTERRUPTED)
            } else {
                // Flush old attacks before holding use; the strike was issued on an earlier tick.
                client.mouse.clearPendingClicks()
                // Clearing clicks also clears pending mouse rotation; restore the retreat steering.
                moveAway()
                session = SupplyUseSession(tick, lockedItem.count(inventory), client.latency)
                stage = Stage.USING
                client.mouse.pressButton(MouseButton.Type.RIGHT_CLICK)
                session?.observe(tick, lockedItem.count(inventory), client.fakePlayer.isEatingOrDrinking)
            }
        }
    }

    private fun prepareStrike() {
        stopMovement() // A setup strike must never chase the opponent.
        val enemy = perception.snapshot().enemies.firstOrNull { it.uuid == strikeTarget }
        if (enemy == null || !enemy.eatingOrDrinking || !enemy.lineOfSightLikelyClear ||
            enemy.distance3D > reach || tick - strikeStartedTick >= SupplyPolicy.STRIKE_WAIT_TICKS) {
            strikeAttempted = true
            stage = Stage.PREPARING
            return
        }
        val inventory = client.fakePlayer.inventory
        val weaponSlot = (0..8).filter { (inventory.getItemStackAt(it)?.attackDamage ?: 0.0) > 0.0 }
            .maxByOrNull { inventory.getItemStackAt(it)?.attackDamage ?: 0.0 }
        if (weaponSlot == null) {
            strikeAttempted = true
            stage = Stage.PREPARING
            return
        }
        if (inventory.heldSlot != weaponSlot) {
            selectSlot(weaponSlot)
            return
        }
        if (!aimStrike(enemy)) return
        issuingStrike = true
        try {
            client.mouse.pressButton(25, MouseButton.Type.LEFT_CLICK)
        } finally {
            issuingStrike = false
        }
        strikeAttempted = true
        strikeTick = tick
        stage = Stage.AFTER_STRIKE
    }

    private fun aimStrike(enemy: CombatPerception.PlayerState): Boolean {
        val player = client.fakePlayer
        val yaw = perception.yawTowards(enemy)
        val targetY = (enemy.entity.boundingBox.minY + enemy.entity.boundingBox.maxY) * 0.5
        val pitch = -Math.toDegrees(atan2(targetY - (player.y + player.eyeHeight), max(0.001, enemy.distance2D))).toFloat()
        val yawError = angleDifference(player.yaw, yaw)
        val pitchError = angleDifference(player.pitch, pitch)
        client.mouse.changeYaw(yawError.coerceIn(-45f * client.configuration.horizontalAimSpeed.coerceAtLeast(0.05f),
            45f * client.configuration.horizontalAimSpeed.coerceAtLeast(0.05f)))
        client.mouse.changePitch(pitchError.coerceIn(-45f * client.configuration.verticalAimSpeed.coerceAtLeast(0.05f),
            45f * client.configuration.verticalAimSpeed.coerceAtLeast(0.05f)))
        return abs(yawError) <= 5f && abs(pitchError) <= 5f
    }

    private fun useOutcome(lockedItem: SupplyItem): SupplyOutcome {
        val use = session ?: return SupplyOutcome.INTERRUPTED
        val result = use.observe(tick, lockedItem.count(client.fakePlayer.inventory), client.fakePlayer.isEatingOrDrinking)
        if (result != SupplyOutcome.IN_PROGRESS) return result
        if (emergencyHealingNeeded()) {
            exitReason = "Emergency healing"
            return SupplyOutcome.INTERRUPTED
        }
        if (use.remaining(tick) > 8 && SupplyPolicy.underClosePressure(perception.snapshot(), reach)) {
            exitReason = "Early close pressure"
            return SupplyOutcome.INTERRUPTED
        }
        return SupplyOutcome.IN_PROGRESS
    }

    private fun emergencyHealingNeeded(): Boolean = client.fakePlayer.health < 6f && SupplyItems.hasHealingSplash(client)
    private fun window(): SupplyWindow = SupplyPolicy.window(perception.snapshot(), reach, client.latency)
    private fun canRetreat(): Boolean = kind == SupplyKind.FOOD && client.fakePlayer.hunger <= 12f &&
        sharedState.retreatReady(tick)

    private fun moveAway(): Boolean {
        val enemy = perception.snapshot().nearestEnemy
        if (enemy == null || (stage != Stage.RETREATING && enemy.distance2D >= 16.0)) {
            stopMovement()
            return true
        }
        val baseYaw = perception.yawAwayFrom(enemy)
        val direction = listOf(baseYaw, baseYaw - 35f, baseYaw + 35f, baseYaw - 70f, baseYaw + 70f)
            .map { it to perception.terrainAhead(it, 0.9) }
            .firstOrNull { (_, terrain) -> terrain.passable }
        if (direction == null) {
            stopMovement()
            return false
        }
        stopMovement()
        client.mouse.changeYaw(angleDifference(client.fakePlayer.yaw, direction.first))
        client.keyboard.pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        if (direction.second.requiresJump && client.fakePlayer.isOnGround) client.keyboard.pressKey(Key.Type.KEY_SPACE)
        return true
    }

    private fun stopMovement() = client.keyboard.unpressKey(Key.Type.KEY_W, Key.Type.KEY_S,
        Key.Type.KEY_A, Key.Type.KEY_D, Key.Type.KEY_SPACE, Key.Type.KEY_LCONTROL)

    private fun end(step: Goal.Tick, reason: String, result: SupplyOutcome) {
        outcome = result
        if (exitReason == "none") exitReason = reason
        step.finishIf("Supply: $exitReason", true)
    }

    fun onEnd() {
        if (outcome == SupplyOutcome.IN_PROGRESS) {
            outcome = SupplyOutcome.TIMED_OUT
            exitReason = "Goal ended before supply finished"
        }
        val failedRetreat = retreatStartedTick != -1 && outcome != SupplyOutcome.COMPLETED
        if (failedRetreat) sharedState.retreatFailed(tick)
        // A failed spacing attempt must not hide a newly opened natural/synchronous window.
        if (!failedRetreat || session != null || strikeAttempted) sharedState.ended(tick, outcome, client.latency)
        // Set IDLE before releasing controls so our own event handler cannot veto the release.
        stage = Stage.IDLE
        session = null
        item = null
        client.fakePlayer.stopUsingItem()
        client.mouse.clearPendingClicks()
        stopMovement()
        if (client.currentScreen != null) client.keyboard.pressKey(10, Key.Type.KEY_ESCAPE)
    }

    fun onEvent(event: Event): Boolean = event is MouseButtonEvent && when {
        event.type == MouseButton.Type.LEFT_CLICK && event.pressed -> !issuingStrike
        event.type == MouseButton.Type.RIGHT_CLICK && !event.pressed -> stage == Stage.USING
        else -> false
    }

    fun onGameLoop() {
        if (stage == Stage.STRIKING) {
            perception.snapshot().enemies.firstOrNull { it.uuid == strikeTarget }?.let(::aimStrike)
        }
        if (stage != Stage.USING) return
        val result = item?.let(::useOutcome) ?: SupplyOutcome.INTERRUPTED
        if (result != SupplyOutcome.IN_PROGRESS) {
            outcome = result
            if (exitReason == "none") exitReason = result.name
            finishGoal()
            return
        }
        if (client.mouse.getButton(MouseButton.Type.RIGHT_CLICK)?.isPressed != true)
            client.mouse.pressButton(MouseButton.Type.RIGHT_CLICK)
    }

    fun debugSummary(): String = "stage=$stage,window=$source,outcome=$outcome,reason=$exitReason," +
        "useTicks=${session?.let { tick - it.startedTick } ?: 0},strike=$strikeAttempted," +
        "retreatTicks=${if (retreatStartedTick == -1) 0 else tick - retreatStartedTick}"
}
