package gg.mineral.bot.ai.goal.type

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsumableUseSessionTest {
    @Test
    fun `inventory count drop completes one apple even when more remain`() {
        val session = ConsumableUseSession(startedTick = 100, initialItemCount = 3)

        assertTrue(session.isComplete(currentTick = 132, currentItemCount = 2, isUsingItem = true))
    }

    @Test
    fun `observed use ending confirms consumption`() {
        val session = ConsumableUseSession(startedTick = 40, initialItemCount = 2)

        assertFalse(session.isComplete(currentTick = 41, currentItemCount = 2, isUsingItem = true))
        assertTrue(session.isComplete(currentTick = 72, currentItemCount = 2, isUsingItem = false))
    }

    @Test
    fun `unobserved use state cannot end the session early`() {
        val session = ConsumableUseSession(startedTick = 10, initialItemCount = 1)

        assertFalse(session.isComplete(currentTick = 43, currentItemCount = 1, isUsingItem = false))
        assertTrue(session.isComplete(currentTick = 44, currentItemCount = 1, isUsingItem = false))
    }

    @Test
    fun `empty consumed slot completes immediately`() {
        val session = ConsumableUseSession(startedTick = 200, initialItemCount = 1)

        assertTrue(session.isComplete(currentTick = 232, currentItemCount = 0, isUsingItem = false))
    }

    @Test
    fun `inventory confirmation timeout covers configured outbound latency`() {
        assertTrue(InventoryMoveTiming.transactionTimeoutTicks(0) == 8)
        assertTrue(InventoryMoveTiming.transactionTimeoutTicks(100) == 8)
        assertTrue(InventoryMoveTiming.transactionTimeoutTicks(200) == 10)
    }

    @Test
    fun `normal apple cannot reenter during effect synchronization grace`() {
        assertFalse(isConsumableReentryReady(109, 100, 10))
        assertTrue(isConsumableReentryReady(110, 100, 10))
    }
}
