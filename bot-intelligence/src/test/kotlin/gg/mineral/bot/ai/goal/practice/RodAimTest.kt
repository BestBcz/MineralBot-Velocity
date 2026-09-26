package gg.mineral.bot.ai.goal.practice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class RodAimTest {
    private fun verifyIntercept(dx: Double, dy: Double, dz: Double, vx: Double = 0.0, vz: Double = 0.0) {
        val aim = requireNotNull(RodAim.solve(dx, dy, dz, vx, 0.0, vz))
        val yaw = Math.toRadians(aim.yaw.toDouble())
        val pitch = Math.toRadians(aim.pitch.toDouble())
        var x = 0.0; var y = 0.0; var z = 0.0
        var mx = -sin(yaw) * cos(pitch) * 1.5
        var my = -sin(pitch) * 1.5
        var mz = cos(yaw) * cos(pitch) * 1.5
        var remaining = aim.flightTicks
        while (remaining > 0.00001) {
            val fraction = min(1.0, remaining)
            x += mx * fraction; y += my * fraction; z += mz * fraction
            mx *= 0.92; my = (my - 0.04) * 0.92; mz *= 0.92
            remaining -= fraction
        }
        val miss = sqrt((x - dx - vx * aim.flightTicks).pow(2) +
            (y - dy).pow(2) + (z - dz - vz * aim.flightTicks).pow(2))
        assertTrue(miss < 0.20, "miss=$miss, aim=$aim")
    }

    @Test fun `stationary targets in every quadrant are hit at body height`() {
        for (distance in listOf(3.5, 6.0, 9.0, 12.0)) {
            verifyIntercept(distance, -0.77, 0.0)
            verifyIntercept(-distance, -0.77, 0.0)
            verifyIntercept(0.0, -0.77, distance)
            verifyIntercept(0.0, -0.77, -distance)
        }
    }
    @Test fun `lead follows strafing approaching and retreating targets`() {
        verifyIntercept(0.0, -0.77, 6.0, 0.28, 0.0)
        verifyIntercept(0.0, -0.77, 6.0, 0.0, -0.28)
        verifyIntercept(0.0, -0.77, 6.0, 0.0, 0.28)
    }
    @Test fun `height difference and invalid targets`() {
        verifyIntercept(0.0, 2.0, 6.0)
        assertNull(RodAim.solve(Double.NaN, 0.0, 5.0, 0.0, 0.0, 0.0))
        assertNull(RodAim.solve(0.0, 0.0, 30.0, 0.0, 0.0, 0.0))
    }
    @Test fun `latency adds horizontal lead`() {
        val immediate = requireNotNull(RodAim.solve(0.0, -0.77, 6.0, 0.25, 0.0, 0.0))
        val delayed = requireNotNull(RodAim.solve(0.0, -0.77, 6.0, 0.25, 0.0, 0.0, 2.0))
        assertTrue(abs(delayed.yaw) > abs(immediate.yaw))
    }
}
