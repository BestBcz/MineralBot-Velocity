package gg.mineral.bot.ai.goal.type

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthPotBurstTest {
    @Test fun `a double throw retains its budget and waits for the last bottle recovery window`() {
        val burst = HealthPotBurst()
        burst.requireTwo()
        burst.thrown(10, 8)
        burst.observeCount(11, 7)
        burst.healed()
        assertFalse(burst.complete(11))
        assertFalse(burst.nextReady(11))
        assertTrue(burst.nextReady(12))
        burst.thrown(12, 7)
        burst.observeCount(13, 6)
        burst.healed()
        assertFalse(burst.complete(28))
        assertTrue(burst.complete(29))
        assertEquals(2, burst.consumedBottles)
        assertFalse(burst.canThrow(100))
        assertFalse(burst.retryReady(100))
        assertThrows(IllegalStateException::class.java) { burst.thrown(100, 6) }
    }

    @Test fun `ignored clicks and elapsed time do not count as potion consumption or healing`() {
        val burst = HealthPotBurst()
        burst.requireTwo()
        burst.thrown(10, 8)
        burst.observeCount(11, 8)
        assertEquals(1, burst.reservedBottles)
        assertFalse(burst.canThrow(25))
        assertFalse(burst.nextReady(100))
        assertFalse(burst.complete(100))
        assertEquals(0, burst.consumedBottles)
        assertTrue(burst.retryReady(26))
        burst.thrown(26, 8)
        assertEquals(1, burst.reservedBottles)
        burst.observeCount(27, 7)
        burst.observeCount(28, 7)
        assertEquals(1, burst.consumedBottles)
        assertTrue(burst.nextReady(28))
    }

    @Test fun `damage after the first throw upgrades the burst to two bottles`() {
        val burst = HealthPotBurst()
        burst.thrown(10, 8)
        burst.observeCount(11, 7)
        burst.requireTwo()
        burst.requireTwo()
        burst.healed()
        assertFalse(burst.complete(14))
        assertTrue(burst.nextReady(14))
        assertEquals(2, burst.requiredBottles)
    }

    @Test fun `a consumed miss settles without granting a replacement bottle`() {
        val burst = HealthPotBurst()
        burst.healed() // Healing before any throw cannot confirm a potion.
        burst.thrown(10, 8)
        burst.observeCount(11, 7)
        assertFalse(burst.complete(26))
        assertTrue(burst.complete(27))
        assertFalse(burst.retryReady(100))
        assertFalse(burst.canThrow(100))
    }

    @Test fun `healing returns combat control without waiting for a delayed inventory update`() {
        val burst = HealthPotBurst()
        burst.thrown(10, 8)
        burst.healed()
        assertTrue(burst.complete(11))
        assertFalse(burst.retryReady(26))
    }

    @Test fun `a missing second bottle cannot lock the bot after healing`() {
        val burst = HealthPotBurst()
        burst.requireTwo()
        burst.thrown(10, 1)
        burst.observeCount(11, 0)
        assertFalse(burst.complete(11, noPotionsRemaining = true))
        burst.healed()
        assertTrue(burst.complete(12, noPotionsRemaining = true))
    }

    @Test fun `one merged health packet cannot finish a double throw before recovery`() {
        val burst = HealthPotBurst()
        burst.requireTwo()
        burst.thrown(10, 8)
        burst.observeCount(11, 7)
        burst.thrown(12, 7)
        burst.healed()
        assertFalse(burst.complete(20))
        assertFalse(burst.retryReady(100))
        assertTrue(burst.complete(28))
        burst.observeCount(29, 6)
        burst.observeCount(30, 6)
        assertEquals(2, burst.consumedBottles)
        assertFalse(burst.canThrow(100))
    }

    @Test fun `recovery and ignored click retries include simulated latency`() {
        for (consumed in listOf(false, true)) {
            val burst = HealthPotBurst()
            burst.thrown(10, 8, latencyMillis = 201)
            if (consumed) burst.observeCount(11, 7)
            assertFalse(burst.retryReady(30))
            assertFalse(burst.complete(31))
            if (consumed) assertTrue(burst.complete(32))
            else assertTrue(burst.retryReady(31))
        }
    }

    @Test fun `inventory corrections stop new clicks while the round settles`() {
        val burst = HealthPotBurst()
        burst.requireTwo()
        burst.thrown(10, 8)
        burst.observeCount(11, 7)
        burst.observeCount(12, 8)
        burst.observeCount(13, 7)
        assertEquals(1, burst.consumedBottles)
        assertFalse(burst.nextReady(20))
        assertFalse(burst.canThrow(100))
        assertFalse(burst.retryReady(100))
        assertTrue(burst.complete(27))
    }

    @Test fun `late consumption after a retry is counted against the round baseline`() {
        val burst = HealthPotBurst()
        burst.requireTwo()
        burst.thrown(10, 8)
        burst.thrown(26, 8)
        burst.observeCount(27, 6)
        assertEquals(2, burst.consumedBottles)
        assertEquals(2, burst.reservedBottles)
        assertFalse(burst.canThrow(100))
        assertFalse(burst.nextReady(100))
        assertTrue(burst.complete(43))
    }
}
