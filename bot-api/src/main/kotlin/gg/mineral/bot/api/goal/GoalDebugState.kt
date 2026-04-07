package gg.mineral.bot.api.goal

/**
 * Optional diagnostic hook for goals that want to explain their current internal state.
 */
interface GoalDebugState {
    fun debugSummary(): String
}
