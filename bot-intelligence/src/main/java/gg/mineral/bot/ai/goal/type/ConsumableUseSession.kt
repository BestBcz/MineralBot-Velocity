package gg.mineral.bot.ai.goal.type

/**
 * Tracks one consumable use without depending on potion-effect packets. A use is complete when the
 * matching inventory count drops, or after a locally observed use stops. The final timeout covers
 * delayed/missing local use-state transitions.
 */
internal class ConsumableUseSession(
    val startedTick: Int,
    val initialItemCount: Int,
    private val fallbackTicks: Int = 34
) {
    private var observedUsingItem = false

    fun isComplete(currentTick: Int, currentItemCount: Int, isUsingItem: Boolean): Boolean {
        if (isUsingItem) observedUsingItem = true

        return currentItemCount < initialItemCount ||
                (observedUsingItem && !isUsingItem) ||
                currentTick - startedTick >= fallbackTicks
    }
}

internal fun isConsumableReentryReady(
    currentTick: Int,
    lastCompletedTick: Int,
    cooldownTicks: Int
): Boolean = currentTick - lastCompletedTick >= cooldownTicks
