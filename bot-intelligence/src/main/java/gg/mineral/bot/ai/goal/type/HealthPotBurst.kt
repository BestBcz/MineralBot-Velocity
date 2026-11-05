package gg.mineral.bot.ai.goal.type

import kotlin.math.ceil

/** A healing round owns its bottle budget; clicks and healing are separate observations. */
internal class HealthPotBurst {
    private data class ThrowAttempt(
        val issuedTick: Int,
        val consumedBefore: Int,
        val recoveryTicks: Int,
        var confirmedTick: Int? = null,
        var healingObserved: Boolean = false
    ) {
        val recoveryDeadline: Int get() = (confirmedTick ?: issuedTick) + recoveryTicks
    }

    var requiredBottles = 1
        private set
    var consumedBottles = 0
        private set
    val awaitingConsumption: Boolean get() = attempt?.confirmedTick == null && attempt != null
    val reservedBottles: Int get() = consumedBottles + if (awaitingConsumption) 1 else 0

    private var initialCount: Int? = null
    private var previousCount: Int? = null
    private var inventoryCorrected = false
    private var attempt: ThrowAttempt? = null
    private var healingObserved = false

    fun requireTwo() { requiredBottles = 2 }

    fun canThrow(tick: Int): Boolean = !inventoryCorrected && consumedBottles < requiredBottles &&
        (attempt == null || nextReady(tick) || retryReady(tick))

    fun thrown(tick: Int, count: Int, latencyMillis: Int = 0) {
        check(canThrow(tick)) { "A potion is still pending or the round's bottle budget is spent" }
        if (initialCount == null) initialCount = count
        previousCount = count
        attempt = ThrowAttempt(tick, consumedBottles, 16 + ceil(latencyMillis.coerceAtLeast(0) / 50.0).toInt())
    }

    fun observeCount(tick: Int, count: Int) {
        val initial = initialCount ?: return
        // An inventory correction makes a new click unsafe until this round has settled.
        if (previousCount?.let { count > it } == true) inventoryCorrected = true
        previousCount = count
        // Use the round's baseline so a delayed update cannot credit a bottle twice.
        consumedBottles = maxOf(consumedBottles, initial - count)
        attempt?.let {
            if (it.confirmedTick == null && consumedBottles > it.consumedBefore) it.confirmedTick = tick
        }
    }

    fun healed() {
        attempt?.let {
            it.healingObserved = true
            healingObserved = true
        }
    }

    fun nextReady(tick: Int): Boolean = attempt?.let {
        !inventoryCorrected && it.confirmedTick != null && consumedBottles < requiredBottles &&
            tick - it.issuedTick >= 2
    } ?: false

    /** Only an unconsumed, unhealed click can retry the same reserved bottle. */
    fun retryReady(tick: Int): Boolean = attempt?.let {
        !inventoryCorrected && it.confirmedTick == null && !it.healingObserved &&
            consumedBottles < requiredBottles && tick >= it.recoveryDeadline
    } ?: false

    fun complete(tick: Int, noPotionsRemaining: Boolean = false): Boolean {
        val last = attempt ?: return false
        if (inventoryCorrected) return tick >= last.recoveryDeadline
        // A single throw can finish on healing even when its inventory update is late.
        if (healingObserved && requiredBottles == 1) return true
        if (last.confirmedTick == null) {
            // Healing with no inventory confirmation is ambiguous, so settle without replaying it.
            return (last.healingObserved || noPotionsRemaining) && tick >= last.recoveryDeadline
        }
        if (consumedBottles < requiredBottles && !noPotionsRemaining) return false
        // Double throws can share one health packet. Neither a missing nor an early health
        // increase proves the last bottle missed or landed; keep its complete recovery window.
        return (consumedBottles == 1 && healingObserved) || tick >= last.recoveryDeadline
    }
}
