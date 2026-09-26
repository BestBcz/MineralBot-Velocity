package gg.mineral.bot.ai.goal.practice

import kotlin.math.*

/** Airborne 1.8 fish hook: launch speed 1.5, drag .92, gravity .04 per tick. */
internal object RodAim {
    data class Solution(val yaw: Float, val pitch: Float, val flightTicks: Double)

    fun solve(dx: Double, dy: Double, dz: Double, vx: Double, vy: Double, vz: Double,
              latencyTicks: Double = 0.0, predictionScale: Double = 1.0): Solution? {
        if (!listOf(dx, dy, dz, vx, vy, vz, latencyTicks, predictionScale).all { it.isFinite() }) return null
        val scale = predictionScale.coerceIn(0.0, 1.5)
        val delay = latencyTicks.coerceIn(0.0, 3.0)
        val speed = hypot(vx, vz)
        val velocityScale = if (speed > 0.6) 0.6 / speed else 1.0
        var travel = 0.0
        var drag = 1.0
        var drop = 0.0
        var fallingSpeed = 0.0
        // Find the earliest reachable intercept, interpolating within each game tick.
        for (tick in 1..24) {
            for (step in 1..8) {
                val fraction = step / 8.0
                val time = tick - 1 + fraction
                val lead = (time + delay) * scale
                val x = dx + vx * velocityScale * lead
                val z = dz + vz * velocityScale * lead
                // Vertical movement changes rapidly during jumps; keep that lead short.
                val y = dy + vy.coerceIn(-0.5, 0.5) * min(lead, 3.0)
                val distanceFactor = travel + drag * fraction
                val lift = y + drop + fallingSpeed * fraction
                if (sqrt(x * x + z * z + lift * lift) / distanceFactor <= 1.5) {
                    return Solution(Math.toDegrees(atan2(-x, z)).toFloat(),
                        -Math.toDegrees(atan2(lift, hypot(x, z))).toFloat(), time)
                }
            }
            travel += drag
            drop += fallingSpeed
            fallingSpeed = (fallingSpeed + 0.04) * 0.92
            drag *= 0.92
        }
        return null
    }
}
