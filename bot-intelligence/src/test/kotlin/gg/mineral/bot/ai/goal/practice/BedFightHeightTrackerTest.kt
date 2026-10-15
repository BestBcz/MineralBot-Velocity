package gg.mineral.bot.ai.goal.practice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BedFightHeightTrackerTest {
    private val a=UUID(0,1)
    private val b=UUID(0,2)
    private fun player(id: UUID=a,height: Double=6.0,grounded: Boolean=true,distance: Double=48.0)=
        BedFightHeightTracker.Opponent(id,height,grounded,distance)

    @Test fun `distant grounded opponents update height immediately and preferred target wins`() {
        val tracker=BedFightHeightTracker()
        assertEquals(6.0,tracker.update(0,listOf(player(),player(b,2.0,distance=16.0)),a))
        assertEquals(7.0,tracker.update(1,listOf(player(height=7.0)),a))
        assertEquals(2.0,tracker.update(2,listOf(player(),player(b,2.0,distance=16.0)),null))
    }

    @Test fun `ordinary jump retains grounded height for at most twenty ticks`() {
        val tracker=BedFightHeightTracker()
        assertNull(tracker.update(0,listOf(player(grounded=false)),a))
        tracker.update(1,listOf(player(height=1.0)),a)
        assertEquals(1.0,tracker.update(21,listOf(player(height=2.2,grounded=false)),a))
        assertNull(tracker.update(22,listOf(player(grounded=false)),a))
        assertEquals(6.0,tracker.update(23,listOf(player()),a))
    }

    @Test fun `death entity loss and reset discard old height samples`() {
        val tracker=BedFightHeightTracker()
        tracker.update(0,listOf(player()),a)
        assertNull(tracker.update(1,emptyList(),a))
        assertNull(tracker.update(2,listOf(player(grounded=false)),a))
        tracker.update(3,listOf(player()),a); tracker.clear()
        assertNull(tracker.update(4,listOf(player(grounded=false)),a))
    }
}
