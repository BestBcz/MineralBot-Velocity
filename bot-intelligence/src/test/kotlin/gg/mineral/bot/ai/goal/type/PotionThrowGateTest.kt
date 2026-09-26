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
        assertTrue(gate.tryIssue(13, -180f, 8f))
    }

    @Test fun `waiting for a delayed projectile never sends another click`() {
        val gate = PotionThrowGate()
        gate.arm(10, 90f, 8f)
        assertTrue(gate.tryIssue(11, 90f, 8f))
        for (tick in 12..25) assertFalse(gate.tryIssue(tick, 90f, 8f))
    }

    @Test fun `invalid rotation is never usable`() {
        val gate = PotionThrowGate()
        gate.arm(10, 90f, 8f)
        assertFalse(gate.tryIssue(11, Float.NaN, 8f))
    }
}
