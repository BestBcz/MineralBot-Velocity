package gg.mineral.bot.ai.navigation

import java.util.UUID

/** Damage tracking is independent of sprint reset and hit-count throttling. */
internal class CombatNavigationProtection(private val millis: () -> Long) {
    private var lastDamage: Long? = null
    private var health: Float? = null
    private var attackTarget: UUID? = null
    private var attackTime: Long? = null
    fun hurt() { lastDamage = millis() }
    fun attacking(target: UUID) { attackTarget=target; attackTime=millis() }
    fun hitFeedback(target: UUID, latencyMillis: Int) {
        val started=attackTime ?: return
        val window=maxOf(250L,latencyMillis.coerceAtLeast(0).toLong()*2+100)
        if (target==attackTarget && millis()-started in 0..window) {
            attackTarget=null; attackTime=null; hurt()
        }
    }
    fun observeHealth(value: Float) {
        if (health?.let { value < it } == true) hurt()
        health = value
    }
    fun clear() { lastDamage = null; attackTarget=null; attackTime=null }
    val active: Boolean get() = lastDamage?.let { millis() - it < NavigationTuning.COMBAT_PROTECTION_MILLIS } == true
}
