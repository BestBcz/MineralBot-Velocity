package gg.mineral.bot.ai.goal.practice

import kotlin.math.hypot

/** Remote motion fields can retain old knockback. Estimate movement from successive positions. */
internal class RodTargetMotion {
    data class Velocity(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)
    private var id = -1
    private var tick = -1
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var velocity = Velocity()

    fun sample(entityId: Int, now: Int, px: Double, py: Double, pz: Double,
               authoritative: Velocity? = null): Velocity {
        if (id == entityId && tick == now) return velocity
        val elapsed = now - tick
        velocity = if (authoritative != null &&
            listOf(authoritative.x, authoritative.y, authoritative.z).all { it.isFinite() }) {
            // Guide positions can arrive unevenly. Their server-provided velocity is per tick,
            // so do not turn a multi-tick position update into a one-tick strafe speed spike.
            val speed = hypot(authoritative.x, authoritative.z)
            val scale = if (speed > 0.6) 0.6 / speed else 1.0
            Velocity(authoritative.x * scale, authoritative.y.coerceIn(-0.5, 0.5), authoritative.z * scale)
        } else if (id != entityId || elapsed !in 1..5 ||
            !listOf(px, py, pz, x, y, z).all { it.isFinite() } ||
            hypot(px - x, pz - z) > 1.2 * elapsed || kotlin.math.abs(py - y) > 1.2 * elapsed) {
            Velocity()
        } else {
            val vx = (px - x) / elapsed
            val vz = (pz - z) / elapsed
            val speed = hypot(vx, vz)
            val scale = if (speed > 0.6) 0.6 / speed else 1.0
            // Favor the latest direction so a strafe reversal cannot keep the old lead alive.
            Velocity(vx * scale * 0.75 + velocity.x * 0.25,
                ((py - y) / elapsed).coerceIn(-0.5, 0.5),
                vz * scale * 0.75 + velocity.z * 0.25)
        }
        id = entityId; tick = now; x = px; y = py; z = pz
        return velocity
    }
}
