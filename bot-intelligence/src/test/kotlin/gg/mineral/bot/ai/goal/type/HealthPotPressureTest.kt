package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.api.configuration.BotDifficulty
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthPotPressureTest {
    @Test fun `normal and pro pressure a low health opponent instead of potting at five hearts`() {
        val config = BotConfiguration(friendlyUUIDs = mutableSetOf())
        for (difficulty in listOf(BotDifficulty.NORMAL, BotDifficulty.PRO)) {
            difficulty.applyTo(config)
            assertTrue(config.healthAdvantagePressureEnabled)
            assertTrue(HealthPotPressure.shouldPressure(10f, 2f, config.healthAdvantagePressureEnabled))
            assertTrue(HealthPotPressure.shouldPressure(10f, 6f, config.healthAdvantagePressureEnabled))
            assertFalse(HealthPotPressure.shouldPressure(10f, 6.1f, config.healthAdvantagePressureEnabled))
        }
    }

    @Test fun `noob never defers healing for a health advantage even after switching from pro`() {
        val config = BotConfiguration(friendlyUUIDs = mutableSetOf())
        BotDifficulty.PRO.applyTo(config)
        BotDifficulty.NOOB.applyTo(config)
        assertFalse(config.healthAdvantagePressureEnabled)
        assertFalse(HealthPotPressure.shouldPressure(10f, 2f, config.healthAdvantagePressureEnabled))
    }

    @Test fun `below three hearts emergency double potting takes priority over any advantage`() {
        assertTrue(HealthPotPressure.shouldPressure(6f, 2f, true))
        assertFalse(HealthPotPressure.shouldPressure(5.9f, 1f, true))
        assertFalse(HealthPotPressure.shouldPressure(4f, 0.1f, true))
    }

    @Test fun `opponent healing and own damage immediately remove permission to pressure`() {
        assertTrue(HealthPotPressure.shouldPressure(10f, 6f, true))
        assertFalse(HealthPotPressure.shouldPressure(10f, 8f, true))
        assertFalse(HealthPotPressure.shouldPressure(8f, 6f, true))
    }

    @Test fun `missing invalid or dead opponent health does not suppress normal healing`() {
        for (health in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 20f)) {
            assertFalse(HealthPotPressure.shouldPressure(10f, health, true))
        }
        assertFalse(HealthPotPressure.shouldPressure(Float.NaN, 2f, true))
    }
}
