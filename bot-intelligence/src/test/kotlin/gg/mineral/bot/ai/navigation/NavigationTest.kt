package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import gg.mineral.bot.api.controls.Key
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class FixtureWorld : NavigationWorld {
    override var revision=0L
    val blocks=mutableMapOf<BlockPos,NavBlock>()
    val gaps=mutableSetOf<Pair<Int,Int>>()
    var building=false
    val breakable=mutableSetOf<BlockPos>()
    fun solid(x: Int,y: Int,z: Int,id: Int=1) {
        blocks[BlockPos(x,y,z)]=NavBlock(id,boxes=listOf(NavBox(x.toDouble(),y.toDouble(),z.toDouble(),x+1.0,y+1.0,z+1.0)),breakTicks=2.0,toolSlot=0)
    }
    override fun block(pos: BlockPos): NavBlock? {
        if(pos.x !in -40..40 || pos.z !in -40..40 || pos.y !in 0..255) return null
        blocks[pos]?.let { return it }
        if(pos.y==0 && (pos.x to pos.z) !in gaps) return NavBlock(1,boxes=listOf(NavBox(pos.x.toDouble(),0.0,pos.z.toDouble(),pos.x+1.0,1.0,pos.z+1.0)))
        return NavBlock(0,replaceable=true)
    }
    override fun canBreak(pos: BlockPos,block: NavBlock)=building && pos in breakable
    override fun canPlace(pos: BlockPos)=building
}

class NavigationTest {
    private fun state(x: Double=0.5,y: Double=1.0,z: Double=0.5,water: Boolean=false)=NavigationState(NavVec(x,y,z),0f,!water,water,300)
    private fun solve(world: FixtureWorld,start: NavigationState=state(),goal: NavVec=NavVec(7.5,1.0,0.5),material: BuildingMaterial?=null): RouteResult {
        val search=IncrementalPathfinder(world,start,NavigationGoal(goal,0.3),material,nanoTime={0})
        var result=search.compute()
        for(i in 1..140) { if(result.status!=SearchStatus.SEARCHING)break; result=search.compute() }
        return result
    }
    @Test fun `search goes around a wall and never cuts the blocked diagonal`() {
        val world=FixtureWorld()
        for(z in -2..2)for(y in 1..3)world.solid(3,y,z)
        val result=solve(world)
        assertEquals(SearchStatus.SUCCESS,result.status,result.toString())
        assertTrue(result.path.any { kotlin.math.abs(it.position.z-0.5)>=3 })
        val geometry=NavigationGeometry(world,0.6,1.8); var previous=state().position
        for(step in result.path) { assertTrue(geometry.walk(previous,step.position,emptyMap())); previous=step.position }
    }
    @Test fun `U shaped enclosure permits moving away from the target to escape`() {
        val world=FixtureWorld()
        for(x in -2..2)for(y in 1..3)world.solid(x,y,3)
        for(z in -2..3)for(y in 1..3) { world.solid(-2,y,z); world.solid(2,y,z) }
        val result=solve(world,goal=NavVec(0.5,1.0,6.5))
        assertEquals(SearchStatus.SUCCESS,result.status,result.toString())
        assertTrue(result.path.any { it.position.z< -1 })
    }
    @Test fun `deep water is traversable and provides upward neighbors`() {
        val world=FixtureWorld()
        for(x in -4..5)for(z in -3..3)for(y in 1..4)world.blocks[BlockPos(x,y,z)]=NavBlock(8,water=true,replaceable=true,flow=NavVec(1.0,0.0,0.0))
        val result=solve(world,state(y=2.0,water=true),NavVec(4.5,4.0,0.5))
        assertEquals(SearchStatus.SUCCESS,result.status,result.toString())
        assertTrue(result.path.all { it.traversal==Traversal.SWIM })
        assertTrue(result.path.any { it.position.y>2 })
    }
    @Test fun `slabs and exact stairs can be stepped without a jump`() {
        val world=FixtureWorld()
        world.blocks[BlockPos(1,1,0)]=NavBlock(53,boxes=listOf(NavBox(1.0,1.0,0.0,2.0,1.5,1.0),NavBox(1.5,1.5,0.0,2.0,2.0,1.0)))
        world.solid(2,1,0)
        val result=solve(world,goal=NavVec(2.5,2.0,0.5))
        assertEquals(SearchStatus.SUCCESS,result.status,result.toString())
        assertTrue(result.path.any { it.traversal==Traversal.STEP })
        assertFalse(result.path.any { it.traversal==Traversal.JUMP })
    }
    @Test fun `a shallow pond with a solid bottom is swimming rather than underwater walking`() {
        val world=FixtureWorld()
        for(x in 1..4)for(z in -1..1)world.blocks[BlockPos(x,1,z)]=NavBlock(9,water=true,replaceable=true)
        val result=solve(world,goal=NavVec(5.5,1.0,0.5))
        assertEquals(SearchStatus.SUCCESS,result.status,result.toString())
        assertTrue(result.path.filter { world.block(it.position.block)?.water==true }.all { it.traversal==Traversal.SWIM })
        // With a nearby dry detour the cost model prefers to walk around the pond.
        assertTrue(result.path.none { it.traversal==Traversal.SWIM })
    }
    @Test fun `when water blocks the whole passage the route still enters it`() {
        val world=FixtureWorld()
        for(x in 1..4)for(z in -40..40)world.blocks[BlockPos(x,1,z)]=NavBlock(8,water=true,replaceable=true)
        val result=solve(world,goal=NavVec(5.5,1.0,0.5))
        assertEquals(SearchStatus.SUCCESS,result.status,result.toString())
        assertTrue(result.path.any { it.traversal==Traversal.SWIM })
    }
    @Test fun `low ceilings and fence shapes remain actual collision obstacles`() {
        val world=FixtureWorld()
        world.blocks[BlockPos(1,1,0)]=NavBlock(85,boxes=listOf(NavBox(1.375,1.0,0.375,1.625,2.5,0.625)))
        assertFalse(NavigationGeometry(world,0.6,1.8).clear(NavVec(1.5,2.0,0.5),emptyMap()))
        world.solid(0,3,0)
        assertFalse(NavigationGeometry(world,0.6,1.8).jump(state().position,NavVec(1.5,2.0,0.5),emptyMap()))
    }
    @Test fun `void and unloaded chunks are not standable air`() {
        val world=FixtureWorld()
        for(x in -40..40)for(z in -40..40)world.gaps.add(x to z)
        val result=solve(world)
        assertEquals(SearchStatus.NO_PATH,result.status)
        assertNull(world.block(BlockPos(41,1,0)))
    }
    @Test fun `bridges account for inventory without altering the source world`() {
        val world=FixtureWorld(); world.building=true
        for(x in 1..5)for(z in -40..40)world.gaps.add(x to z)
        val material=BuildingMaterial(35,0,2,8)
        val result=solve(world,material=material)
        assertEquals(SearchStatus.SUCCESS,result.status,result.toString())
        val actions=result.path.flatMap { it.actions }
        assertTrue(actions.any { it.kind==BlockActionKind.PLACE })
        assertTrue(actions.count { it.kind==BlockActionKind.PLACE }<=8)
        assertEquals(0,world.block(BlockPos(1,0,0))!!.id)
        val insufficient=solve(world,material=material.copy(count=1))
        assertNotEquals(SearchStatus.SUCCESS,insufficient.status)
    }
    @Test fun `only authorized obstacles are excavated`() {
        val world=FixtureWorld(); world.building=true
        for(x in -1..1)for(z in -1..1)if(x!=0 || z!=0)for(y in 1..3)world.solid(x,y,z)
        world.breakable.add(BlockPos(1,1,0)); world.breakable.add(BlockPos(1,2,0))
        val result=solve(world)
        assertEquals(SearchStatus.SUCCESS,result.status,result.toString())
        assertTrue(result.path.flatMap { it.actions }.all { it.pos in world.breakable })
        assertTrue(result.path.any { it.actions.isNotEmpty() })
    }
    @Test fun `time and node budgets yield instead of finishing a large search in one tick`() {
        val world=FixtureWorld(); var nanos=0L
        val search=IncrementalPathfinder(world,state(),NavigationGoal(NavVec(30.5,1.0,30.5),0.3),nanoTime={ nanos+=1_000_001; nanos })
        val result=search.compute()
        assertEquals(SearchStatus.SEARCHING,result.status)
        assertEquals(1,result.expanded)
        val nodes=IncrementalPathfinder(world,state(),NavigationGoal(NavVec(30.5,1.0,30.5),0.3),nanoTime={0}).compute(2)
        assertEquals(2,nodes.expanded)
    }
    private class Context(val fixture: FixtureWorld=FixtureWorld()) : NavigationContext {
        override val world get()=fixture
        var player=NavigationState(NavVec(0.5,1.0,0.5),0f,true,false,300)
        var keys=emptySet<Key.Type>()
        var status=ActionStatus.IDLE
        var material: BuildingMaterial?=null
        override var searchBudget=NavigationSearchBudget.UNLIMITED
        val requests=mutableListOf<BlockAction>()
        override fun state()=player
        override fun material(): BuildingMaterial?=material
        override fun move(keys: Set<Key.Type>) { this.keys=keys }
        override fun requestAction(action: BlockAction): ActionStatus { requests.add(action); status=ActionStatus.WAITING; return status }
        override fun actionStatus()=status
        override fun tickAction() {}
        override fun cancelAction() { keys=emptySet(); status=ActionStatus.IDLE }
        override fun reset()=cancelAction()
    }
    @Test fun `water control floats and never sprints even without a target`() {
        val ctx=Context(); ctx.player=ctx.player.copy(inWater=true,onGround=false)
        val navigator=CombatNavigator(ctx)
        assertTrue(navigator.update(1,null,2.5,false,true))
        assertTrue(Key.Type.KEY_SPACE in ctx.keys)
        assertFalse(Key.Type.KEY_LCONTROL in ctx.keys)
        navigator.pause(); assertTrue(ctx.keys.isEmpty())
    }
    @Test fun `low air gives floating priority and close combat returns movement ownership`() {
        val ctx=Context(); ctx.player=ctx.player.copy(inWater=true,onGround=false,air=40)
        val navigator=CombatNavigator(ctx)
        navigator.update(1,NavVec(7.5,1.0,0.5),2.5,false,true)
        assertEquals(setOf(Key.Type.KEY_SPACE),ctx.keys)
        ctx.player=ctx.player.copy(inWater=false,onGround=true,air=300)
        assertFalse(navigator.update(2,NavVec(1.5,1.0,0.5),2.5,true,true))
        assertTrue(ctx.keys.isEmpty())
    }
    @Test fun `gap jumps wait for the turn and then use forward sprint controls`() {
        val ctx=Context()
        for(x in 1..2)for(z in -40..40)ctx.fixture.gaps.add(x to z)
        val navigator=CombatNavigator(ctx)
        var tick=0
        while(!navigator.preparingJump && tick<100) { tick++; navigator.update(tick,NavVec(7.5,1.0,0.5),0.3,false,true) }
        assertTrue(navigator.preparingJump,navigator.diagnostic)
        assertFalse(Key.Type.KEY_SPACE in ctx.keys)
        ctx.player=ctx.player.copy(yaw=requireNotNull(navigator.steeringYaw))
        navigator.update(tick+1,NavVec(7.5,1.0,0.5),0.3,false,true)
        assertTrue(Key.Type.KEY_W in ctx.keys)
        assertTrue(Key.Type.KEY_LCONTROL in ctx.keys)
        assertTrue(Key.Type.KEY_SPACE in ctx.keys)
    }
    @Test fun `pending block confirmation waits without counting as stuck and utility can interrupt`() {
        val ctx=Context(); ctx.fixture.building=true
        for(x in -1..1)for(z in -1..1)if(x!=0 || z!=0)for(y in 1..3)ctx.fixture.solid(x,y,z)
        ctx.fixture.breakable.add(BlockPos(1,1,0)); ctx.fixture.breakable.add(BlockPos(1,2,0))
        val navigator=CombatNavigator(ctx)
        var tick=0
        while(!navigator.ownsInteraction && tick<100) { tick++; navigator.update(tick,NavVec(7.5,1.0,0.5),0.3,false,true) }
        assertTrue(navigator.ownsInteraction,navigator.diagnostic)
        for(i in 1..60) navigator.update(tick+i,NavVec(7.5,1.0,0.5),0.3,false,true)
        assertEquals(1,ctx.requests.size)
        assertTrue(navigator.ownsInteraction)
        navigator.pause()
        assertFalse(navigator.ownsInteraction)
        assertTrue(ctx.keys.isEmpty())
        assertEquals(ActionStatus.IDLE,ctx.status)
    }
    @Test fun `a rejected operation is excluded instead of repeatedly clicking the same block`() {
        val ctx=Context(); ctx.fixture.building=true
        for(x in -1..1)for(z in -1..1)if(x!=0 || z!=0)for(y in 1..3)ctx.fixture.solid(x,y,z)
        ctx.fixture.breakable.add(BlockPos(1,1,0)); ctx.fixture.breakable.add(BlockPos(1,2,0))
        val navigator=CombatNavigator(ctx); var tick=0
        while(!navigator.ownsInteraction && tick<100) { tick++; navigator.update(tick,NavVec(7.5,1.0,0.5),0.3,false,true) }
        assertTrue(navigator.ownsInteraction)
        ctx.status=ActionStatus.FAILED
        for(i in 1..40) navigator.update(tick+i,NavVec(7.5,1.0,0.5),0.3,false,true)
        assertEquals(1,ctx.requests.size)
        assertFalse(navigator.ownsInteraction)
    }
    @Test fun `knockback out of an unreachable enclosure resumes navigation without a block update`() {
        val ctx=Context()
        for(x in -1..1)for(z in -1..1)if(x!=0 || z!=0)for(y in 1..3)ctx.fixture.solid(x,y,z)
        val navigator=CombatNavigator(ctx)
        for(tick in 1..50) navigator.update(tick,NavVec(7.5,1.0,0.5),0.3,false,true)
        assertTrue(navigator.searchesStarted>=3, "unreachable terrain should retry instead of sleeping indefinitely")
        ctx.player=ctx.player.copy(position=NavVec(2.5,1.0,0.5))
        navigator.update(51,NavVec(7.5,1.0,0.5),0.3,false,true)
        assertEquals("direct",navigator.diagnostic)
        assertFalse(ctx.keys.isEmpty())
    }
}
