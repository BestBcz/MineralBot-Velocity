package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BedFightPolicyTest {
    private val a=UUID(0,1); private val b=UUID(0,2)
    private fun state(aLife: BedFightLife=BedFightLife.ALIVE,bLife: BedFightLife=BedFightLife.ALIVE,
                      bed: Boolean=true,selfRespawning: Boolean=false,active: Boolean=true)=
        BedFightState(UUID(1,2),UUID(3,4),1,active,selfRespawning,
            listOf(EnemyBed(1,bed,setOf(BlockPos(10,1,0),BlockPos(11,1,0)))),
            listOf(BedFightOpponent(a,aLife,if(aLife==BedFightLife.RESPAWNING)60 else 0),BedFightOpponent(b,bLife)))
    private fun threats(vararg pairs: Pair<UUID,Double>)=pairs.map { BedFightPolicy.Threat(it.first,it.second) }

    @Test fun `ten blocks includes the boundary and twelve blocks holds combat`() {
        val policy=BedFightPolicy()
        assertEquals(BedFightPolicy.Mode.BED,policy.update(0,state(),threats(a to 10.01),false).mode)
        assertEquals(a,policy.update(1,state(),threats(a to 10.0),false).target)
        for(tick in 2..30) assertEquals(BedFightPolicy.Mode.COMBAT,policy.update(tick,state(),threats(a to 12.0),false).mode)
        assertEquals(BedFightPolicy.Mode.COMBAT,policy.update(31,state(),threats(a to 12.01),false).mode)
        assertEquals(BedFightPolicy.Mode.COMBAT,policy.update(50,state(),threats(a to 12.01),false).mode)
        assertEquals(BedFightPolicy.Mode.BED,policy.update(51,state(),threats(a to 12.01),false).mode)
    }
    @Test fun `fresh combat protection delays return even after the outside buffer`() {
        val policy=BedFightPolicy(); policy.update(0,state(),threats(a to 2.0),true)
        policy.update(1,state(),threats(a to 13.0),false)
        assertEquals(BedFightPolicy.Mode.COMBAT,policy.update(30,state(),threats(a to 13.0),true).mode)
        assertEquals(BedFightPolicy.Mode.BED,policy.update(31,state(),threats(a to 13.0),false).mode)
    }
    @Test fun `confirmed temporary death immediately opens a bed window and ignores corpse entities`() {
        val policy=BedFightPolicy(); policy.update(0,state(),threats(a to 2.0),true)
        val decision=policy.update(1,state(BedFightLife.RESPAWNING),threats(a to 2.0,b to 30.0),true)
        assertEquals(BedFightPolicy.Mode.BED,decision.mode); assertTrue(decision.clearCombat)
        for(tick in 2..60) assertEquals(BedFightPolicy.Mode.BED,
            policy.update(tick,state(BedFightLife.RESPAWNING),threats(a to 2.0),false).mode)
        assertEquals(BedFightPolicy.Mode.COMBAT,policy.update(61,state(),threats(a to 9.0),false).mode)
    }
    @Test fun `a different nearby opponent keeps combat after a kill and becomes the target`() {
        val policy=BedFightPolicy(); policy.update(0,state(),threats(a to 2.0,b to 15.0),true)
        val decision=policy.update(1,state(BedFightLife.RESPAWNING),threats(a to 2.0,b to 8.0),true)
        assertEquals(BedFightPolicy.Mode.COMBAT,decision.mode); assertEquals(b,decision.target)
        assertFalse(decision.clearCombat)
    }
    @Test fun `missing entities do not count as confirmed deaths and boundary reentry resets the buffer`() {
        val policy=BedFightPolicy(); policy.update(0,state(),threats(a to 5.0),false)
        assertEquals(BedFightPolicy.Mode.COMBAT,policy.update(1,state(),emptyList(),false).mode)
        policy.update(19,state(),threats(a to 11.0),false)
        policy.update(20,state(),threats(a to 13.0),false)
        assertEquals(BedFightPolicy.Mode.COMBAT,policy.update(39,state(),emptyList(),false).mode)
        assertEquals(BedFightPolicy.Mode.BED,policy.update(40,state(),emptyList(),false).mode)
    }
    @Test fun `destroyed bed hunts survivors while our respawn and ended match stop controls`() {
        val policy=BedFightPolicy()
        val hunt=policy.update(0,state(bed=false),threats(a to 40.0,b to 20.0),false)
        assertEquals(BedFightPolicy.Mode.HUNT,hunt.mode); assertEquals(b,hunt.target)
        assertEquals(BedFightPolicy.Mode.WAIT,policy.update(1,state(selfRespawning=true),threats(a to 2.0),false).mode)
        assertEquals(BedFightPolicy.Mode.WAIT,policy.update(2,state(active=false),emptyList(),false).mode)
        assertEquals(BedFightPolicy.Mode.FALLBACK,policy.update(3,null,emptyList(),false).mode)
    }
}
