package gg.mineral.bot.ai.goal.type

/** Normal/Pro may trade their health advantage for pressure, but never defer emergency healing. */
internal object HealthPotPressure {
    fun shouldPressure(selfHealth: Float, opponentHealth: Float?, enabled: Boolean): Boolean =
        enabled && selfHealth.isFinite() && selfHealth >= 6f &&
            opponentHealth != null && opponentHealth.isFinite() && opponentHealth > 0f &&
            selfHealth - opponentHealth >= 4f
}
