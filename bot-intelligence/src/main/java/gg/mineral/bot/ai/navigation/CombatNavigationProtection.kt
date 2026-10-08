package gg.mineral.bot.ai.navigation

/** Damage tracking is independent of sprint reset and hit-count throttling. */
internal class CombatNavigationProtection(private val millis: () -> Long) {
    private var lastDamage: Long? = null
    private var health: Float? = null
    fun hurt() { lastDamage = millis() }
    fun observeHealth(value: Float) {
        if (health?.let { value < it } == true) hurt()
        health = value
    }
    val active: Boolean get() = lastDamage?.let { millis() - it < 5_000 } == true
}
