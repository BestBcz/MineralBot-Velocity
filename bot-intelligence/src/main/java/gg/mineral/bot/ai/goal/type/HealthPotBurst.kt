package gg.mineral.bot.ai.goal.type

/** Count actual inventory consumption, so an ignored click does not count as a bottle. */
internal class HealthPotBurst {
    var requiredBottles = 1
        private set
    var consumedBottles = 0
        private set
    private var countBeforeThrow = 0
    private var throwTick = -1
    private var consumed = false
    private var healed = false

    fun requireTwo() { requiredBottles = 2 }

    fun thrown(tick: Int, count: Int) {
        throwTick = tick
        countBeforeThrow = count
        consumed = false
        healed = false
    }

    fun observeCount(count: Int) {
        if (throwTick >= 0 && !consumed && count < countBeforeThrow) {
            consumed = true
            consumedBottles++
        }
    }

    fun healed() { if (throwTick >= 0) healed = true }
    fun hasMore() = consumedBottles < requiredBottles
    fun nextReady(tick: Int) = consumed && hasMore() && tick - throwTick >= 2
    // Observed healing is enough to release combat control even if the inventory update is late.
    fun complete(noPotionsRemaining: Boolean = false) = healed &&
        (noPotionsRemaining || consumedBottles + (if (consumed) 0 else 1) >= requiredBottles)
    fun missed(tick: Int) = throwTick >= 0 && !healed && tick - throwTick >= 16
}
