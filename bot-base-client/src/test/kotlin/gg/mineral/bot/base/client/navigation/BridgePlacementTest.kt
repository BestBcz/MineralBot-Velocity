package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.Vec3

class BridgePlacementTest {
    @Test fun `ten consecutive blocks in all four directions keep a backward view and release sneak on confirmed support`() {
        for((dx,dz) in listOf(1 to 0,-1 to 0,0 to 1,0 to -1)) {
            var previousYaw: Float?=null
            for(step in 1..10) {
                val support=BlockPos((step-1)*dx,0,(step-1)*dz)
                val target=support.offset(dx,0,dz)
                val bridge=BridgePlacement(support,target,{ p,width ->
                    val along=(p.x-0.5)*dx+(p.z-0.5)*dz
                    along+width/2+0.015<=step-0.5 && along-width/2-0.015>= -0.5
                })
                val startAlong=if(step==1) 0.0 else -0.35
                fun at(along: Double)=NavigationState(NavVec(support.x+0.5+along*dx,1.0,
                    support.z+0.5+along*dz),0f,true,false,300)
                assertFalse(bridge.update(at(startAlong),false).sneak,"confirmed block $step direction $dx,$dz")
                for(along in listOf(startAlong,0.35,0.55,0.65)) {
                    val state=at(along); val aim=bridge.aim(state)
                    if(along<=0.5) assertFalse(aim.sideVisible)
                    previousYaw?.let { assertEquals(it,aim.yaw) }
                    previousYaw=aim.yaw
                    // The look direction always opposes the direction of construction.
                    val yaw=Math.toRadians(aim.yaw.toDouble())
                    assertEquals(-1.0,-sin(yaw)*dx+cos(yaw)*dz,1e-6)
                    if(along==0.65) {
                        assertTrue(aim.sideVisible)
                        val motion=bridge.update(state,false)
                        assertTrue(motion.ready); assertTrue(motion.sneak)
                        assertTrue(bridge.update(state,true).sneak)
                        // Intersect the actual aim ray with the clicked side's plane.
                        val pitch=Math.toRadians(aim.pitch.toDouble())
                        val rayAlong=-cos(pitch)
                        val t=(0.5-along)/rayAlong
                        val hitY=state.position.y+state.eyeHeight-sin(pitch)*t
                        assertEquals(0.5,hitY,1e-5)
                        assertTrue(t in 0.0..4.5)
                        val eye=Vec3.createVectorHelper(state.position.x,state.position.y+state.eyeHeight,state.position.z)
                        val end=eye.addVector(-sin(yaw)*cos(pitch)*4.5,-sin(pitch)*4.5,cos(yaw)*cos(pitch)*4.5)
                        val hit=AxisAlignedBB.getBoundingBox(support.x.toDouble(),0.0,support.z.toDouble(),
                            support.x+1.0,1.0,support.z+1.0).calculateIntercept(eye,end)
                        assertNotNull(hit)
                        assertEquals(when { dx>0 -> 5; dx<0 -> 4; dz>0 -> 3; else -> 2 },hit.sideHit)
                    }
                }
            }
        }
    }
    private fun state(x: Double,v: Double=0.0)=NavigationState(NavVec(x,1.0,0.5),0f,true,false,300,velocity=NavVec(v,0.0,0.0))
    private fun supported(lastBlock: Int): (NavVec,Double)->Boolean = { p,width ->
        p.x-width/2-0.015>=0 && p.x+width/2+0.015<=lastBlock+1.0 && abs(p.y-1.0)<0.06
    }
    @Test fun `confirmed interior uses fast movement and two tick edge prediction switches to sneak`() {
        val bridge=BridgePlacement(BlockPos(0,0,0),BlockPos(1,0,0),supported(0))
        assertFalse(bridge.update(state(0.5),false).sneak)
        assertTrue(bridge.update(state(0.65,0.10),false).sneak)
        assertTrue(bridge.update(state(1.1,0.03),false).ready)
        assertTrue(bridge.update(state(1.1,0.03),true).sneak)
    }
    @Test fun `an unconfirmed destination never grants fast movement across the edge`() {
        val bridge=BridgePlacement(BlockPos(0,0,0),BlockPos(1,0,0),supported(0))
        val waiting=bridge.update(state(1.15,0.02),true)
        assertTrue(waiting.sneak); assertTrue(waiting.ready)
    }
    @Test fun `knockback and loss of height leave the fast rhythm`() {
        val bridge=BridgePlacement(BlockPos(0,0,0),BlockPos(1,0,0),supported(0))
        assertTrue(bridge.update(state(0.5,0.4),false).sneak)
        assertTrue(bridge.update(state(0.6),false).sneak)
        assertTrue(bridge.update(state(0.6).copy(position=NavVec(0.6,0.7,0.5)),false).failed)
    }
    @Test fun `turning starts cautiously even on a wide confirmed platform`() {
        val bridge=BridgePlacement(BlockPos(0,0,0),BlockPos(0,0,1),{ _,_ -> true },initialCautious=true)
        assertTrue(bridge.update(state(0.5),false).sneak)
    }
    @Test fun `ten block physics fixture reduces bridging ticks by at least thirty percent`() {
        // Vanilla ground acceleration/friction, one-tick authorization and confirmation.
        // This isolates movement and preparation; live ray/input/network timing is verified in-server.
        fun simulate(fast: Boolean,lag: Int): Int {
            var x=0.5; var velocity=0.0; var tick=0
            for(block in 1..10) {
                val bridge=BridgePlacement(BlockPos(block-1,0,0),BlockPos(block,0,0),supported(block-1))
                val edge=block+0.15
                var preparing=0
                if(!fast) tick+=lag
                while(true) {
                    tick++; preparing++
                    assertTrue(tick<1_000)
                    val motion=bridge.update(state(x,velocity),false)
                    val ready=if(fast) motion.ready else abs(edge-x)<=0.09
                    if(ready && (!fast || preparing>=lag)) break
                    val sign=sign(edge-x)
                    val acceleration=if(!fast || motion.sneak) 0.03 else 0.10
                    velocity+=sign*acceleration
                    // Sneak clips motion at the last confirmed platform.
                    x=(x+velocity).coerceIn(0.31,block+0.29)
                    velocity*=0.546
                }
                tick+=lag; velocity=0.0
            }
            return tick
        }
        val slow=simulate(false,1); val fast=simulate(true,1)
        println("Bridge ten-block fixture: baseline=$slow ticks fast=$fast ticks reduction=${(slow-fast)*100.0/slow}%")
        assertTrue(fast<=slow*0.70,"baseline=$slow fast=$fast")
        for(lag in listOf(2,5)) assertTrue(simulate(true,lag)<simulate(false,lag))
    }
}
