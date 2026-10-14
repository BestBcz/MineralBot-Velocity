package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BlockInteractionTest {
    private val beds=setOf(BlockPos(5,1,0),BlockPos(5,1,1))
    private fun world()=FixtureWorld().also { w ->
        w.building=true
        for(p in beds) {
            w.blocks[p]=NavBlock(26,boxes=listOf(NavBox(p.x.toDouble(),1.0,p.z.toDouble(),p.x+1.0,1.5625,p.z+1.0)),breakTicks=4.0,toolSlot=1)
            w.breakable.add(p)
        }
    }
    private fun state(x: Double=2.5)=NavigationState(NavVec(x,1.0,0.5),0f,true,false,300)
    @Test fun `interaction chooses removable defence then exposes a bed half`() {
        val w=world()
        for(z in -2..3) for(y in 1..3) { w.solid(4,y,z,121); w.breakable.add(BlockPos(4,y,z)) }
        var first: BlockAction?=null; var last: BlockAction?=null
        for(i in 0..8) {
            val action=BlockInteraction.findAction(w,state(),beds) ?: error("no action after $i")
            if(first==null) first=action
            last=action
            if(action.pos in beds) break
            w.blocks[action.pos]=NavBlock(0,replaceable=true); w.revision++
        }
        assertEquals(121,first!!.blockId); assertEquals(26,last!!.blockId)
        assertNotNull(first.aimPoint)
    }
    @Test fun `protected walls unloaded beds and out of reach beds cannot produce actions`() {
        val w=world()
        assertNull(BlockInteraction.findAction(w,state(-0.5),beds))
        assertNull(BlockInteraction.findAction(w,state(),setOf(BlockPos(50,1,0))))
        for(z in -2..3) for(y in 1..3) w.solid(4,y,z)
        assertNull(BlockInteraction.findAction(w,state(),beds))
    }
    @Test fun `standing on either bed half does not excavate the bot support`() {
        val w=world()
        assertNull(BlockInteraction.findAction(w,state().copy(position=NavVec(5.5,1.5625,0.5)),beds))
        assertNull(BlockInteraction.findAction(w,state().copy(onGround=false),beds))
        assertNull(BlockInteraction.findAction(w,state().copy(inWater=true),beds))
    }
    @Test fun `a protected obstacle selects a different supported interaction position`() {
        val w=world()
        for(z in -1..1) for(y in 1..3) w.solid(4,y,z)
        val search=IncrementalPathfinder(w,state(),NavigationGoal(NavVec(5.5,1.5,0.5),4.5,beds),nanoTime={0})
        var result=search.compute()
        repeat(140) { if(result.status==SearchStatus.SEARCHING) result=search.compute() }
        assertEquals(SearchStatus.SUCCESS,result.status)
        assertTrue(result.path.isNotEmpty())
        assertTrue(result.path.all { it.actions.isEmpty() })
        assertEquals(26,BlockInteraction.findAction(w,state().copy(position=result.path.last().position),beds)!!.blockId)
    }
    @Test fun `a nearby block goal starts digging without entity combat handoff`() {
        val w=world()
        var action: BlockAction?=null
        val ctx=object : NavigationContext {
            override val world=w
            override fun state()=this@BlockInteractionTest.state()
            override fun material(): BuildingMaterial?=null
            override fun requestAction(a: BlockAction): ActionStatus { action=a; return ActionStatus.WAITING }
            override fun actionStatus()=if(action==null) ActionStatus.IDLE else ActionStatus.WAITING
            override fun tickAction() {}
            override fun cancelAction() { action=null }
            override fun reset()=cancelAction()
        }
        val nav=CombatNavigator(ctx)
        assertTrue(nav.update(1,NavVec(5.5,1.5,0.5),4.5,true,true,interactionBlocks=beds))
        assertEquals(26,action!!.blockId); assertTrue(nav.ownsInteraction)
        nav.reset(); assertNull(action)
    }
}
