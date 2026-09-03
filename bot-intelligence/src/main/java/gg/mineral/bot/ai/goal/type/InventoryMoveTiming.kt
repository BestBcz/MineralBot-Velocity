package gg.mineral.bot.ai.goal.type

import kotlin.math.ceil
import kotlin.math.max

internal object InventoryMoveTiming {
    fun transactionTimeoutTicks(latencyMillis: Int): Int {
        return max(8, ceil(max(0, latencyMillis) / 50.0).toInt() + 6)
    }

    fun legacySettleTicks(latencyMillis: Int): Int {
        return max(3, ceil(max(0, latencyMillis) / 50.0).toInt() + 2)
    }
}
