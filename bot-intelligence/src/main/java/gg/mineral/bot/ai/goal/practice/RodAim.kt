package gg.mineral.bot.ai.goal.practice

import kotlin.math.*

/** Airborne 1.8 fish hook: launch speed 1.5, drag .92, gravity .04 per tick. */
internal object RodAim {
    data class Solution(val yaw: Float, val pitch: Float, val flightTicks: Double)

    /** API y is bounding-box feet, while the legacy client's eyeHeight is only .12 above posY.
     * The hook actually starts at feet + 1.62 - .1; aim at the current torso height. */
    fun solveFromFeet(dx: Double, selfFeetY: Double, targetFeetY: Double, dz: Double,
                      vx: Double, vz: Double, latencyTicks: Double = 0.0,
                      predictionScale: Double = 1.0): Solution? =
        solve(dx, targetFeetY + 0.9 - (selfFeetY + 1.52), dz,
            vx, 0.0, vz, latencyTicks, predictionScale)

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
        // Find the first intercept and solve its sub-tick time. A coarse reachable sample
        // assumes a slower launch speed and overshoots when the hook actually launches at 1.5.
        for (tick in 1..24) {
            fun requiredSpeed(fraction: Double): Double {
                val lead = (tick - 1 + fraction + delay) * scale
                val x = dx + vx * velocityScale * lead
                val z = dz + vz * velocityScale * lead
                val y = dy + vy.coerceIn(-0.5, 0.5) * min(lead, 3.0)
                val lift = y + drop + fallingSpeed * fraction
                return sqrt(x * x + z * z + lift * lift) / (travel + drag * fraction)
            }
            for (step in 1..8) {
                var fraction = step / 8.0
                if (requiredSpeed(fraction) > 1.5) continue
                var low = (step - 1) / 8.0
                var high = fraction
                repeat(24) {
                    val middle = (low + high) / 2
                    if (requiredSpeed(middle) > 1.5) low = middle else high = middle
                }
                fraction = high
                val time = tick - 1 + fraction
                val lead = (time + delay) * scale
                val x = dx + vx * velocityScale * lead
                val z = dz + vz * velocityScale * lead
                // Vertical movement changes rapidly during jumps; keep that lead short.
                val y = dy + vy.coerceIn(-0.5, 0.5) * min(lead, 3.0)
                val lift = y + drop + fallingSpeed * fraction
                return Solution(Math.toDegrees(atan2(-x, z)).toFloat(),
                    -Math.toDegrees(atan2(lift, hypot(x, z))).toFloat(), time)
            }
            travel += drag
            drop += fallingSpeed
            fallingSpeed = (fallingSpeed + 0.04) * 0.92
            drag *= 0.92
        }
        return null
    }
}
