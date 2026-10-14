package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class BridgePlacementTest {
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
