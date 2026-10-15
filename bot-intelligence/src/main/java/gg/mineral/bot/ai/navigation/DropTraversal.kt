package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import kotlin.math.*

/** Commit to a landing while falling; overshooting its center must not turn the view around. */
internal class DropTraversal(val from: NavVec,val to: NavVec,private val started: Int) {
    enum class Phase { APPROACHING, FALLING, LANDED, FAILED }
    val yaw=Math.toDegrees(atan2(-(to.x-from.x),to.z-from.z)).toFloat()
    private var airborne=false

    fun update(tick: Int,state: NavigationState): Phase {
        if(tick-started>150 || state.position.y<to.y-0.3) return Phase.FAILED
        if(!state.onGround) airborne=true
        if(state.onGround && abs(state.position.y-to.y)<0.3 &&
            hypot(state.position.x-to.x,state.position.z-to.z)<=0.65) return Phase.LANDED
        if(airborne && state.onGround) return Phase.FAILED
        return if(airborne) Phase.FALLING else Phase.APPROACHING
    }
}
