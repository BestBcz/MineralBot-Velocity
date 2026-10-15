package gg.mineral.bot.ai.goal.practice

import java.util.UUID

/** Grounded feet heights avoid treating an ordinary jump as a new bridge level. */
internal class BedFightHeightTracker {
    data class Opponent(val uuid: UUID,val feet: Double,val onGround: Boolean,val distance: Double)
    private data class Sample(val height: Double,val tick: Int)
    private val grounded=mutableMapOf<UUID,Sample>()

    fun update(tick: Int,opponents: List<Opponent>,preferred: UUID?): Double? {
        val present=opponents.map { it.uuid }.toSet()
        grounded.keys.retainAll(present)
        for(player in opponents) if(player.onGround && player.feet.isFinite())
            grounded[player.uuid]=Sample(player.feet,tick)
        grounded.entries.removeIf { tick-it.value.tick>20 }
        val known=opponents.filter { it.uuid in grounded }
        val player=known.firstOrNull { it.uuid==preferred } ?: known.minByOrNull { it.distance }
        return player?.let { grounded[it.uuid]?.height }
    }

    fun clear()=grounded.clear()
}
