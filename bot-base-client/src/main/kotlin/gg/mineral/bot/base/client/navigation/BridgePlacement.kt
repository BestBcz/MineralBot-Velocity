package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.api.navigation.*
import kotlin.math.*

/** Fast travel only over confirmed support; the edge and confirmation wait always use sneak. */
internal class BridgePlacement(private val support: BlockPos, private val target: BlockPos,
                               private val supported: (NavVec,Double) -> Boolean, initialCautious: Boolean = false) {
    data class Motion(val destination: NavVec, val sneak: Boolean, val ready: Boolean, val failed: Boolean = false)
    data class Aim(val yaw: Float, val pitch: Float, val sideVisible: Boolean)
    private var cautious=initialCautious
    private var previous: NavVec?=null
    private val dx=(target.x-support.x).toDouble()
    private val dz=(target.z-support.z).toDouble()
    private val edge=NavVec(support.x+0.5+dx*0.65,support.y+1.0,support.z+0.5+dz*0.65)

    fun aim(state: NavigationState): Aim {
        val face=NavVec(support.x+0.5+dx*0.5,support.y+0.5,support.z+0.5+dz*0.5)
        val along=(state.position.x-face.x)*dx+(state.position.z-face.z)*dz
        val beyond=along>0.01
        // The next side is still ahead until the feet pass its plane. Never turn
        // around to aim at that hidden face during travel across the new block.
        val pitch=if(beyond) Math.toDegrees(atan2(state.position.y+state.eyeHeight-face.y,
            along)).toFloat() else 80f
        return Aim(Math.toDegrees(atan2(dx,-dz)).toFloat(),pitch,beyond)
    }

    fun update(state: NavigationState,clicked: Boolean): Motion {
        val here=state.position
        if(!state.onGround || hypot(state.velocity.x,state.velocity.z)>0.35 ||
            previous?.let { hypot(here.x-it.x,here.z-it.z)>0.45 }==true) cautious=true
        previous=here
        if(here.y<support.y+0.8 || abs(here.y-support.y-1.0)>0.3 || abs(dx)+abs(dz)!=1.0)
            return Motion(edge,true,false,true)
        val distance=hypot(edge.x-here.x,edge.z-here.z)
        val ready=distance<=0.12
        val predicted=NavVec(here.x+state.velocity.x*2+dx*0.08,here.y,here.z+state.velocity.z*2+dz*0.08)
        val sneak=cautious || clicked || ready || !supported(here,state.width) || !supported(predicted,state.width)
        return Motion(edge,sneak,ready)
    }
}
