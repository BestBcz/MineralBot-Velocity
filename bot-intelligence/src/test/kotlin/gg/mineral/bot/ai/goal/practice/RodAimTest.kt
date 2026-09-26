package gg.mineral.bot.ai.goal.practice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class RodAimTest {
    private fun simulate(aim: RodAim.Solution, launchY: Double): DoubleArray {
        val yaw = Math.toRadians(aim.yaw.toDouble())
        val pitch = Math.toRadians(aim.pitch.toDouble())
        val position = doubleArrayOf(0.0, launchY, 0.0)
        var mx = -sin(yaw) * cos(pitch) * 1.5
        var my = -sin(pitch) * 1.5
        var mz = cos(yaw) * cos(pitch) * 1.5
        var remaining = aim.flightTicks
        while (remaining > 0.000001) {
            val fraction = min(1.0, remaining)
            position[0] += mx * fraction; position[1] += my * fraction; position[2] += mz * fraction
            mx *= 0.92; my = (my - 0.04) * 0.92; mz *= 0.92
            remaining -= fraction
        }
        return position
    }

    @Test fun `legacy eye height does not make the real hook pass over the target head`() {
        val feet = 64.0
        for (distance in listOf(2.5, 4.0, 6.0, 9.0, 12.0)) {
            val fixed = requireNotNull(RodAim.solveFromFeet(0.0, feet, feet, distance, 0.0, 0.0))
            val hit = simulate(fixed, feet + 1.62 - 0.1)
            assertEquals(feet + 0.9, hit[1], 0.001, "distance=$distance, aim=$fixed")
            assertEquals(distance, hit[2], 0.001)
        }
        // Reproduce the previous caller error using the actual .12 legacy eyeHeight.
        val old = requireNotNull(RodAim.solve(0.0, 0.9 - (0.12 - 0.1), 6.0, 0.0, 0.0, 0.0))
        assertTrue(simulate(old, feet + 1.52)[1] > feet + 1.8)
    }

    @Test fun `feet based casts hit strafing targets at torso height across directions and elevations`() {
        val feet = 64.0
        for (distance in listOf(3.0, 6.0, 9.0)) {
            for (direction in listOf(-1.0, 1.0)) {
                for (targetFeet in listOf(63.5, 64.0, 65.0)) {
                    val velocity = direction * 0.28
                    val aim = requireNotNull(RodAim.solveFromFeet(0.0, feet, targetFeet, distance,
                        velocity, 0.0, 1.0))
                    val hit = simulate(aim, feet + 1.52)
                    assertEquals(velocity * (aim.flightTicks + 1.0), hit[0], 0.001)
                    assertEquals(targetFeet + 0.9, hit[1], 0.001)
                    assertEquals(distance, hit[2], 0.001)
                }
            }
        }
    }

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
        assertTrue(miss < 0.001, "miss=$miss, aim=$aim")
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
