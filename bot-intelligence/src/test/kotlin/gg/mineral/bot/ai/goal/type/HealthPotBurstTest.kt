package gg.mineral.bot.ai.goal.type

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthPotBurstTest {
    @Test fun `first bottle healing does not interrupt a required double throw`() {
        val burst = HealthPotBurst()
        burst.requireTwo()
        burst.thrown(10, 8)
        burst.observeCount(7)
        burst.healed()
        assertFalse(burst.complete())
        assertFalse(burst.nextReady(11))
        assertTrue(burst.nextReady(12))
        burst.thrown(12, 7)
        burst.observeCount(6)
        assertFalse(burst.complete())
        burst.healed()
        assertTrue(burst.complete())
        assertEquals(2, burst.consumedBottles)
    }

    @Test fun `ignored clicks and elapsed time do not count as potion consumption or healing`() {
        val burst = HealthPotBurst()
        burst.requireTwo()
        burst.thrown(10, 8)
        burst.observeCount(8)
        assertFalse(burst.nextReady(100))
        assertFalse(burst.complete())
        assertEquals(0, burst.consumedBottles)
        burst.thrown(101, 8)
        burst.observeCount(7)
        burst.observeCount(7)
        assertEquals(1, burst.consumedBottles)
        assertTrue(burst.nextReady(105))
    }

    @Test fun `damage after the first throw upgrades the burst to two bottles`() {
        val burst = HealthPotBurst()
        burst.thrown(10, 8)
        burst.observeCount(7)
        burst.requireTwo()
        burst.requireTwo()
        burst.healed()
        assertFalse(burst.complete())
        assertTrue(burst.nextReady(14))
        assertEquals(2, burst.requiredBottles)
    }

    @Test fun `normal single throw still requires observed healing`() {
        val burst = HealthPotBurst()
        burst.healed() // Healing before any throw cannot confirm a potion.
        burst.thrown(10, 8)
        burst.observeCount(7)
        assertFalse(burst.hasMore())
        assertFalse(burst.complete())
        burst.healed()
        assertTrue(burst.complete())
    }

    @Test fun `a miss is retried after exactly point eight seconds and resets after the new throw`() {
        val burst = HealthPotBurst()
        burst.thrown(10, 8)
        burst.observeCount(7)
        assertFalse(burst.missed(25))
        assertTrue(burst.missed(26))
        assertFalse(burst.complete())
        burst.thrown(26, 7)
        assertFalse(burst.missed(41))
        assertTrue(burst.missed(42))
        burst.healed()
        assertFalse(burst.missed(42))
        assertTrue(burst.complete())
    }

    @Test fun `healing returns combat control without waiting for a delayed inventory update`() {
        val burst = HealthPotBurst()
        burst.thrown(10, 8)
        burst.healed()
        assertTrue(burst.complete())
        assertFalse(burst.missed(26))
    }

    @Test fun `a missing second bottle cannot lock the bot after healing`() {
        val burst = HealthPotBurst()
        burst.requireTwo()
        burst.thrown(10, 1)
        burst.observeCount(0)
        assertFalse(burst.complete(noPotionsRemaining = true))
        burst.healed()
        assertTrue(burst.complete(noPotionsRemaining = true))
    }
}
