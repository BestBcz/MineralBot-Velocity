package gg.mineral.bot.api.configuration

enum class BotDifficulty(
    val id: String,
    val displayName: String,
    private val averageCps: Float,
    private val cpsDeviation: Float,
    private val horizontalAimSpeed: Float,
    private val verticalAimSpeed: Float,
    private val horizontalAimAcceleration: Float,
    private val verticalAimAcceleration: Float,
    private val horizontalAimMaxAccel: Float,
    private val verticalAimMaxAccel: Float,
    private val horizontalAimAccuracy: Float,
    private val verticalAimAccuracy: Float,
    private val horizontalErraticness: Float,
    private val verticalErraticness: Float,
    private val reach: Float,
    private val sprintResetAccuracy: Float,
    private val hitSelectAccuracy: Float,
    private val predictionHorizon: Int,
    private val pearlCooldown: Int,
    private val rodCooldownTicks: Int,
    private val rodMinRange: Double,
    private val rodMaxRange: Double,
    private val rodCancelRange: Double,
    private val rodPredictionMultiplier: Double,
    private val rodPitchBias: Float,
    private val pearlHealthThreshold: Float,
    private val strafeActivationRange: Double,
    private val potAccuracy: Double
) {
    NOOB(
        "noob",
        "Noob",
        7.0f,
        1.75f,
        0.34f,
        0.32f,
        1.008f,
        1.008f,
        1.18f,
        1.18f,
        0.32f,
        0.30f,
        0.78f,
        0.74f,
        2.88f,
        0.33f,
        0.30f,
        3,
        22,
        15,
        2.8,
        8.6,
        3.3,
        1.9,
        4.5f,
        18.0f,
        2.35,
        0.38
    ),
    NORMAL(
        "normal",
        "Normal",
        10.0f,
        1.0f,
        0.5f,
        0.5f,
        1.015f,
        1.015f,
        1.5f,
        1.5f,
        0.5f,
        0.5f,
        0.5f,
        0.5f,
        3.0f,
        0.5f,
        0.5f,
        5,
        15,
        9,
        2.2,
        12.0,
        3.05,
        2.9,
        0.0f,
        16.0f,
        2.95,
        0.5
    ),
    PRO(
        "pro",
        "Pro",
        12.5f,
        0.8f,
        0.72f,
        0.68f,
        1.03f,
        1.03f,
        1.85f,
        1.78f,
        0.86f,
        0.82f,
        0.16f,
        0.14f,
        3.08f,
        0.82f,
        0.78f,
        7,
        8,
        6,
        2.0,
        13.2,
        2.9,
        3.35,
        -1.5f,
        14.0f,
        3.15,
        0.72
    );

    fun applyTo(configuration: BotConfiguration) {
        configuration.averageCps = averageCps
        configuration.cpsDeviation = cpsDeviation
        configuration.horizontalAimSpeed = horizontalAimSpeed
        configuration.verticalAimSpeed = verticalAimSpeed
        configuration.horizontalAimAcceleration = horizontalAimAcceleration
        configuration.verticalAimAcceleration = verticalAimAcceleration
        configuration.horizontalAimMaxAccel = horizontalAimMaxAccel
        configuration.verticalAimMaxAccel = verticalAimMaxAccel
        configuration.horizontalAimAccuracy = horizontalAimAccuracy
        configuration.verticalAimAccuracy = verticalAimAccuracy
        configuration.horizontalErraticness = horizontalErraticness
        configuration.verticalErraticness = verticalErraticness
        configuration.reach = reach
        configuration.sprintResetAccuracy = sprintResetAccuracy
        configuration.hitSelectAccuracy = hitSelectAccuracy
        configuration.predictionHorizon = predictionHorizon
        configuration.pearlCooldown = pearlCooldown
        configuration.rodCooldownTicks = rodCooldownTicks
        configuration.rodMinRange = rodMinRange
        configuration.rodMaxRange = rodMaxRange
        configuration.rodCancelRange = rodCancelRange
        configuration.rodPredictionMultiplier = rodPredictionMultiplier
        configuration.rodPitchBias = rodPitchBias
        configuration.pearlHealthThreshold = pearlHealthThreshold
        configuration.strafeActivationRange = strafeActivationRange
        configuration.potAccuracy = potAccuracy
    }

    companion object {
        @JvmStatic
        fun fromId(id: String?): BotDifficulty {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NORMAL
        }
    }
}
