package gg.mineral.bot.ai.goal.type

/** Damage pressure persists across goal attempts; quiet time restarts after every hit. */
internal class HealthPotTiming {
    private var lastHitTick = -100
    private var previousHealth: Float? = null
    var hitVersion = 0
        private set

    fun hit(tick: Int) { lastHitTick = tick; hitVersion++ }

    fun observe(tick: Int, health: Float): Boolean {
        val previous = previousHealth
        if (previous != null && health < previous) hit(tick)
        previousHealth = health
        return previous != null && health > previous
    }

    fun settled(tick: Int) = tick - lastHitTick >= 10

    fun shouldCounterattack(health: Float, enemyDistance: Double, hitDuringAttempt: Boolean) =
        hitDuringAttempt && health >= 6f && enemyDistance <= 2.5
}
