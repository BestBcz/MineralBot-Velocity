package gg.mineral.bot.api.instance

import java.util.UUID

/** Shared by a bot's combat goals; accessed on that bot's game loop. */
class CombatTargetState {
    var targetUuid: UUID? = null
    var lastSwitchTick: Int = Int.MIN_VALUE
    var lastEvaluationTick: Int = Int.MIN_VALUE

    fun reset() {
        targetUuid = null
        lastSwitchTick = Int.MIN_VALUE
        lastEvaluationTick = Int.MIN_VALUE
    }
}
