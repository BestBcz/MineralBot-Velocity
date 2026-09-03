package gg.mineral.bot.base.client.inventory

import gg.mineral.bot.api.inv.InventoryTransactionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InventoryTransactionTrackerTest {
    @Test
    fun `accepts only the matching acknowledgement and retains prediction snapshots`() {
        val tracker = InventoryTransactionTracker<String>()
        val entry = requireNotNull(tracker.register(0, 7, 10, 44, 8, "old-hotbar", "potion"))

        assertTrue(tracker.hasPending())
        assertNull(tracker.complete(1, 7, true))
        assertNull(tracker.complete(0, 8, true))
        assertEquals(InventoryTransactionStatus.PENDING, tracker.status(entry.token))

        val accepted = requireNotNull(tracker.complete(0, 7, true))
        assertEquals(InventoryTransactionStatus.ACCEPTED, tracker.status(entry.token))
        assertFalse(tracker.hasPending())
        assertEquals("old-hotbar", accepted.sourceAfter)
        assertEquals("potion", accepted.hotbarAfter)
        assertEquals(listOf(accepted), tracker.consumeAcceptedForSlot(0, 10))
        assertTrue(tracker.consumeAcceptedForSlot(0, 10).isEmpty())
        assertEquals(listOf(accepted), tracker.consumeAcceptedForSlot(0, 44))
    }

    @Test
    fun `rejection blocks another swap until the full window resynchronizes`() {
        val tracker = InventoryTransactionTracker<String>()
        val rejected = requireNotNull(tracker.register(0, 11, 12, 44, 8, null, "pearl"))

        tracker.complete(0, 11, false)

        assertEquals(InventoryTransactionStatus.REJECTED, tracker.status(rejected.token))
        assertTrue(tracker.hasPending())
        assertNull(tracker.register(0, 12, 13, 44, 8, null, "potion"))

        tracker.markWindowSynchronized(2)
        assertTrue(tracker.hasPending())
        tracker.markWindowSynchronized(0)
        assertFalse(tracker.hasPending())
        requireNotNull(tracker.register(0, 12, 13, 44, 8, null, "potion"))
    }

    @Test
    fun `pending transaction never permits a duplicate request`() {
        val tracker = InventoryTransactionTracker<String>()
        requireNotNull(tracker.register(0, 20, 9, 44, 8, null, "splash potion"))

        repeat(20) {
            assertNull(tracker.register(0, (21 + it).toShort(), 10, 44, 8, null, "duplicate"))
        }
    }

    @Test
    fun `zero one-hundred and two-hundred millisecond confirmations stay single-shot`() {
        for (confirmationTicks in listOf(0, 2, 4)) {
            val tracker = InventoryTransactionTracker<String>()
            val entry =
                    requireNotNull(
                            tracker.register(0, 22, 14, 44, 8, "old-slot", "potion")
                    )

            repeat(confirmationTicks) {
                assertEquals(InventoryTransactionStatus.PENDING, tracker.status(entry.token))
                assertNull(tracker.register(0, 23, 16, 44, 8, null, "duplicate"))
            }

            tracker.complete(0, 22, true)
            assertEquals(InventoryTransactionStatus.ACCEPTED, tracker.status(entry.token))
        }
    }

    @Test
    fun `accepted prediction survives one stale full window update`() {
        val tracker = InventoryTransactionTracker<String>()
        val entry = requireNotNull(tracker.register(0, 25, 15, 44, 8, "sword", "potion"))
        tracker.complete(0, 25, true)

        assertEquals(listOf(entry), tracker.consumeAcceptedForWindow(0))
        assertTrue(tracker.consumeAcceptedForWindow(0).isEmpty())
    }

    @Test
    fun `forgotten completed token becomes unknown`() {
        val tracker = InventoryTransactionTracker<String>()
        val entry = requireNotNull(tracker.register(0, 30, 9, 44, 8, null, "food"))
        tracker.complete(0, 30, true)

        tracker.forget(entry.token)

        assertEquals(InventoryTransactionStatus.UNKNOWN, tracker.status(entry.token))
        assertTrue(tracker.consumeAcceptedForWindow(0).isEmpty())
    }
}
