package gg.mineral.bot.ai.goal.practice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RodTargetMotionTest {
    @Test fun `guide velocity stays consistent through uneven position updates and strafe reversal`() {
        val motion = RodTargetMotion()
        val right = RodTargetMotion.Velocity(0.28, 0.0, 0.0)
        val left = RodTargetMotion.Velocity(-0.28, 0.0, 0.0)
        assertEquals(right, motion.sample(1, 0, 0.0, 0.0, 6.0, right))
        assertEquals(right, motion.sample(1, 1, 0.0, 0.0, 6.0, right))
        assertEquals(right, motion.sample(1, 2, 0.84, 0.0, 6.0, right))
        assertEquals(left, motion.sample(1, 3, 0.56, 0.0, 6.0, left))
        assertEquals(RodTargetMotion.Velocity(), motion.sample(1, 4, 0.56, 0.0, 6.0,
            RodTargetMotion.Velocity()))
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
