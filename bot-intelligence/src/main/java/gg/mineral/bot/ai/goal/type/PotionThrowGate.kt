package gg.mineral.bot.ai.goal.type

import kotlin.math.abs

/** Wait starts when the actual view reaches the escape direction, not when aim is requested. */
internal class PotionThrowGate(private val waitTicks: Int = 1) {
    private var armedTick = -1
    private var yaw = 0f
    private var pitch = 0f
    private var alignedTick = -1
    var issued = false
        private set

    fun arm(tick: Int, yaw: Float, pitch: Float) {
        this.armedTick = tick
        this.yaw = yaw
        this.pitch = pitch
        issued = false
        alignedTick = -1
    }

    fun tryIssue(tick: Int, actualYaw: Float, actualPitch: Float, safeToThrow: Boolean = true): Boolean {
        if (issued || armedTick < 0 || tick <= armedTick) return false
        if (!actualYaw.isFinite() || !actualPitch.isFinite() || !yaw.isFinite() || !pitch.isFinite()) {
            alignedTick = -1
            return false
        }
        val yawError = ((actualYaw - yaw) % 360f + 540f) % 360f - 180f
        if (abs(yawError) > 5f || abs(actualPitch - pitch) > 2f || !safeToThrow) {
            alignedTick = -1
            return false
        }
        if (alignedTick < 0) alignedTick = tick
        if (tick - alignedTick < waitTicks.coerceAtLeast(0)) return false
        issued = true
        return true
    }
}
