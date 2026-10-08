package gg.mineral.bot.ai.goal.practice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RodTargetMotionTest {
    @Test fun `position samples across multiple ticks estimate per tick movement`() {
        val motion = RodTargetMotion()
        motion.sample(1, 0, 0.0, 0.0, 6.0)
        assertEquals(0.21, motion.sample(1, 3, 0.84, 0.0, 6.0).x, 0.001)
        assertTrue(motion.sample(1, 4, 0.56, 0.0, 6.0).x < 0)
    }

    @Test fun `strafe reversal immediately reverses lead and stopping clears it`() {
        val motion = RodTargetMotion()
        motion.sample(1, 0, 0.0, 0.0, 6.0)
        assertTrue(motion.sample(1, 1, 0.3, 0.0, 6.0).x > 0)
        assertTrue(motion.sample(1, 2, 0.0, 0.0, 6.0).x < 0)
        for (tick in 3..6) motion.sample(1, tick, 0.0, 0.0, 6.0)
        assertEquals(0.0, motion.sample(1, 7, 0.0, 0.0, 6.0).x, 0.001)
    }

    @Test fun `teleport target switch and long sampling gaps discard old velocity`() {
        val motion = RodTargetMotion()
        motion.sample(1, 0, 0.0, 0.0, 6.0)
        motion.sample(1, 1, 0.3, 0.0, 6.0)
        assertEquals(RodTargetMotion.Velocity(), motion.sample(1, 2, -50.0, 0.0, 6.0))
        assertEquals(RodTargetMotion.Velocity(), motion.sample(2, 3, 5.0, 0.0, 6.0))
        motion.sample(2, 4, 5.3, 0.0, 6.0)
        assertEquals(RodTargetMotion.Velocity(), motion.sample(2, 20, 5.6, 0.0, 6.0))
    }

    @Test fun `sampling the same tick twice does not change velocity`() {
        val motion = RodTargetMotion()
        motion.sample(1, 0, 0.0, 0.0, 6.0)
        val velocity = motion.sample(1, 1, 0.3, 0.0, 6.0)
        assertEquals(velocity, motion.sample(1, 1, 0.3, 0.0, 6.0))
    }
}
