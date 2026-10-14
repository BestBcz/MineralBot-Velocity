package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.api.navigation.*
import java.util.UUID

/** One policy owns both combat and bed navigation; distance changes use tick hysteresis. */
internal class BedFightPolicy {
    enum class Mode { FALLBACK, COMBAT, BED, HUNT, WAIT }
    data class Threat(val uuid: UUID, val distance: Double)
    data class Decision(val mode: Mode, val target: UUID? = null, val clearCombat: Boolean = false)
    private var fighting=false
    private var target: UUID?=null
    private var outsideSince: Int?=null

    fun update(tick: Int,state: BedFightState?,enemies: List<Threat>,protected: Boolean): Decision {
        if(state==null) { reset(); return Decision(Mode.FALLBACK) }
        if(!state.active || state.selfRespawning) { reset(); return Decision(Mode.WAIT,clearCombat=true) }
        val life=state.opponents.associate { it.uuid to it.life }
        val nearest=enemies.filter { life[it.uuid]==BedFightLife.ALIVE }.minByOrNull { it.distance }
        if(state.beds.none { it.alive && it.blocks.isNotEmpty() }) {
            val dead=target?.let { life[it] in setOf(BedFightLife.RESPAWNING,BedFightLife.ELIMINATED) }==true
            target=nearest?.uuid
            return Decision(Mode.HUNT,target,clearCombat=dead)
        }
        val previousDied=target?.let { life[it] in setOf(BedFightLife.RESPAWNING,BedFightLife.ELIMINATED) }==true
        if(previousDied && (nearest==null || nearest.distance>12.0)) {
            reset(); return Decision(Mode.BED,clearCombat=true)
        }
        if(nearest!=null && (nearest.distance<=10.0 || protected)) fighting=true
        if(fighting) {
            if(nearest==null || nearest.distance>12.0) {
                if(outsideSince==null) outsideSince=tick
                if(tick-outsideSince!!>=20 && !protected) reset()
            } else outsideSince=null
        }
        if(!fighting) return Decision(Mode.BED)
        if(nearest!=null) target=nearest.uuid
        return Decision(Mode.COMBAT,target)
    }
    private fun reset() { fighting=false; target=null; outsideSince=null }
}
