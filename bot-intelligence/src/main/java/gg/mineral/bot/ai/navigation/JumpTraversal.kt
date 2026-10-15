package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.NavVec
import gg.mineral.bot.api.navigation.NavigationState
import kotlin.math.abs
import kotlin.math.hypot

/** One takeoff per attempt, including time spent aligning before takeoff. */
internal class JumpTraversal(val from: NavVec, val to: NavVec, private val started: Int) {
    enum class Phase { PREPARING, TAKEOFF, AIRBORNE, LANDED, FAILED }
    var phase=Phase.PREPARING; private set
    private var launched=-1
    private var leftGround=false
    fun update(tick: Int, state: NavigationState, aligned: Boolean): Phase {
        val reached=hypot(state.position.x-to.x,state.position.z-to.z)<=0.4 && abs(state.position.y-to.y)<=0.3
        phase=when {
            state.onGround && reached -> Phase.LANDED
            tick-started>=NavigationTuning.JUMP_ATTEMPT_TICKS -> Phase.FAILED
            !state.onGround -> { leftGround=true; Phase.AIRBORNE }
            leftGround || launched>=0 && tick-launched>2 -> Phase.FAILED
            launched>=0 -> Phase.AIRBORNE
            aligned -> { launched=tick; Phase.TAKEOFF }
            else -> Phase.PREPARING
        }
        return phase
    }
}
