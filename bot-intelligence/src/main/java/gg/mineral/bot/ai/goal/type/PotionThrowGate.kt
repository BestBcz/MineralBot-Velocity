package gg.mineral.bot.ai.goal.type

import kotlin.math.abs

/** A validated aim must survive a game tick before one use input may be issued. */
internal class PotionThrowGate {
    private var armedTick = -1
    private var yaw = 0f
    private var pitch = 0f
    var issued = false
        private set

    fun arm(tick: Int, yaw: Float, pitch: Float) {
        this.armedTick = tick
        this.yaw = yaw
        this.pitch = pitch
        issued = false
    }

    fun tryIssue(tick: Int, actualYaw: Float, actualPitch: Float): Boolean {
        if (issued || armedTick < 0 || tick <= armedTick) return false
        if (!actualYaw.isFinite() || !actualPitch.isFinite() || !yaw.isFinite() || !pitch.isFinite()) return false
        val yawError = ((actualYaw - yaw) % 360f + 540f) % 360f - 180f
        if (abs(yawError) > 5f || abs(actualPitch - pitch) > 2f) return false
        issued = true
        return true
    }
}
