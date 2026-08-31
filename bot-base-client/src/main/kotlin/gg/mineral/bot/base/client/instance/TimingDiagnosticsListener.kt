package gg.mineral.bot.base.client.instance

fun interface TimingDiagnosticsListener {
    fun onTimingEvent(
        event: String,
        queuedNanos: Long,
        velocityX: Double,
        velocityY: Double,
        velocityZ: Double
    )
}
