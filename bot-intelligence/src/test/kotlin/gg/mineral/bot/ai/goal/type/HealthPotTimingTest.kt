package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.api.configuration.BotDifficulty
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthPotTimingTest {
    @Test fun `each difficulty applies the requested potion delay`() {
        val config = BotConfiguration(friendlyUUIDs = mutableSetOf())
        for ((difficulty, delay) in listOf(BotDifficulty.NOOB to 16, BotDifficulty.NORMAL to 12, BotDifficulty.PRO to 6)) {
            difficulty.applyTo(config)
            assertEquals(delay, config.healthPotAimWaitTicks)
        }
    }

    @Test fun `repeated hits postpone the quiet window without changing the trigger threshold`() {
        val timing = HealthPotTiming()
        timing.observe(0, 10f)
        assertFalse(timing.observe(1, 8f))
        timing.observe(8, 6f)
        assertFalse(timing.settled(17))
        assertTrue(timing.settled(18))
        assertEquals(2, timing.hitVersion)
    }

    @Test fun `only a hit during preparation at close range permits counterattack above emergency health`() {
        val timing = HealthPotTiming()
        assertFalse(timing.shouldCounterattack(10f, 2.0, false))
        assertTrue(timing.shouldCounterattack(10f, 2.5, true))
        assertTrue(timing.shouldCounterattack(6f, 2.0, true))
        assertFalse(timing.shouldCounterattack(5.9f, 2.0, true))
        assertFalse(timing.shouldCounterattack(4f, 2.0, true))
        assertFalse(timing.shouldCounterattack(10f, 2.51, true))
    }

    @Test fun `healing is observed even if damage has lowered health below the prethrow value`() {
        val timing = HealthPotTiming()
        assertFalse(timing.observe(0, 4f))
        assertFalse(timing.observe(2, 2f))
        assertFalse(timing.observe(30, 2f)) // Time alone is never a heal confirmation.
        assertTrue(timing.observe(31, 3f))
        assertFalse(timing.observe(32, 3f))
    }
}
