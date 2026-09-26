package gg.mineral.bot.ai.goal.type

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PotionThrowGateTest {
    @Test fun `waits for actual rotation and a subsequent game tick`() {
        val gate = PotionThrowGate()
        assertFalse(gate.tryIssue(9, 180f, 8f))
        gate.arm(10, 180f, 8f)
        assertFalse(gate.tryIssue(10, 180f, 8f))
        assertFalse(gate.tryIssue(11, 0f, 8f))
        assertFalse(gate.tryIssue(12, 180f, 0f))
        assertFalse(gate.tryIssue(13, -180f, 8f))
        assertTrue(gate.tryIssue(14, -180f, 8f))
    }

    @Test fun `waiting for a delayed projectile never sends another click`() {
        val gate = PotionThrowGate()
        gate.arm(10, 90f, 8f)
        assertFalse(gate.tryIssue(11, 90f, 8f))
        assertTrue(gate.tryIssue(12, 90f, 8f))
        for (tick in 13..25) assertFalse(gate.tryIssue(tick, 90f, 8f))
    }

    @Test fun `invalid rotation is never usable`() {
        val gate = PotionThrowGate()
        gate.arm(10, 90f, 8f)
        assertFalse(gate.tryIssue(11, Float.NaN, 8f))
    }

    @Test fun `difficulty delay begins after the view actually turns`() {
        for (wait in listOf(16, 12, 6)) {
            val gate = PotionThrowGate(wait)
            gate.arm(10, 180f, 85f)
            for (tick in 11..20) assertFalse(gate.tryIssue(tick, 0f, 85f))
            for (tick in 21 until 21 + wait) assertFalse(gate.tryIssue(tick, -180f, 85f))
            assertTrue(gate.tryIssue(21 + wait, -180f, 85f))
        }
    }

    @Test fun `side hit restarts the wait and prevents a queued throw`() {
        val gate = PotionThrowGate(10)
        gate.arm(0, 90f, 85f)
        for (tick in 1..10) assertFalse(gate.tryIssue(tick, 90f, 85f))
        assertFalse(gate.tryIssue(11, 90f, 85f, safeToThrow = false))
        for (tick in 12..21) assertFalse(gate.tryIssue(tick, 90f, 85f))
        assertTrue(gate.tryIssue(22, 90f, 85f))
    }

    @Test fun `immediate followup skips the delay but still requires actual aligned rotation`() {
        val gate = PotionThrowGate(0)
        gate.arm(9, 90f, 8f)
        assertFalse(gate.tryIssue(10, 0f, 8f))
        assertTrue(gate.tryIssue(11, 90f, 8f))
        assertFalse(gate.tryIssue(12, 90f, 8f))
    }
}
