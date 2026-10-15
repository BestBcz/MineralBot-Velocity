package gg.mineral.bot.ai.navigation

/** Selection preferences are separate from physical execution times. */
internal object NavigationTuning {
    const val COMBAT_PROTECTION_MILLIS = 6_000L
    const val COMBAT_MIN_RANGE = 4.0
    const val COMBAT_REACH_MARGIN = 0.75
    const val COMBAT_HEIGHT = 1.25
    const val TAKEOVER_TICKS = 4
    const val TERRAIN_COST_FACTOR = 0.70
    const val BRIDGE_EXECUTION_TICKS = 4.0
    const val EXECUTABLE_PREFIX_STEPS = 3
    const val MAX_TERRAIN_EDITS = 12
    const val SEARCH_RADIUS = 32.0
    const val JUMP_ATTEMPT_TICKS = 24
    const val FAILED_JUMP_TICKS = 100
}
