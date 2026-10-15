package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

/** Deterministic terrain/confirmation fixtures; these do not simulate Minecraft networking. */
class ConstructionNavigationTest {
    private class World(var island: Int=48) : NavigationWorld {
        override var revision=0L
        val placed=mutableMapOf<BlockPos,NavBlock>()
        var enabled=true
        var heightLimit=255
        var loadedThrough=80
        var ceiling: Int?=null
        var groundEverywhere=false
        var laneOnly=false
        fun solid(p: BlockPos)=NavBlock(35,boxes=listOf(NavBox(p.x.toDouble(),p.y.toDouble(),p.z.toDouble(),p.x+1.0,p.y+1.0,p.z+1.0)))
        override fun block(pos: BlockPos): NavBlock? {
            if(pos.x !in -80..loadedThrough || pos.z !in -80..80 || pos.y !in 0..255) return null
            placed[pos]?.let { return it }
            return if(pos.y==0 && (groundEverywhere || pos.x<=0 || pos.x>=island) || pos.y==ceiling)
                solid(pos) else NavBlock(0,replaceable=true)
        }
        override fun canPlace(pos: BlockPos)=enabled && pos.y<=heightLimit && (!laneOnly || pos.z==0)
        override fun canBreak(pos: BlockPos,block: NavBlock)=false
    }
    private class Context(val terrain: World=World()) : NavigationContext {
        override val world get()=terrain
        override var searchBudget=object : NavigationSearchBudget { override fun acquire(owner: Any)=Long.MAX_VALUE }
        var player=NavigationState(NavVec(0.5,1.0,0.5),-90f,true,false,300)
        var blocks: BuildingMaterial?=BuildingMaterial(35,0,2,64)
        var keys=emptySet<Key.Type>()
        var status=ActionStatus.IDLE
        val requests=mutableListOf<BlockAction>()
        var cancellations=0
        var bridgeTravel=false
        override val ownsAim get()=bridgeTravel
        override fun continueBridge(destination: NavVec?): Boolean {
            if(destination==null) bridgeTravel=false
            return bridgeTravel
        }
        override fun state()=player
        override fun material()=blocks
        override fun move(keys: Set<Key.Type>) { this.keys=keys }
        override fun requestAction(action: BlockAction): ActionStatus {
            assertEquals(ActionStatus.IDLE,status,"only one outstanding action")
            requests.add(action); status=ActionStatus.WAITING; keys=setOf(Key.Type.KEY_LSHIFT); return status
        }
        override fun actionStatus()=status
        override fun tickAction() {}
        override fun cancelAction() { cancellations++; status=ActionStatus.IDLE; keys=emptySet(); bridgeTravel=false }
        override fun completeAction() { status=ActionStatus.IDLE; keys=emptySet() }
        override fun reset()=cancelAction()
        fun confirmAndLand() {
            val action=requests.last()
            assertTrue(terrain.canPlace(action.pos))
            terrain.placed[action.pos]=terrain.solid(action.pos); terrain.revision++
            blocks=blocks!!.copy(count=blocks!!.count-1); status=ActionStatus.CONFIRMED
            // Advancing this fixture is allowed only after the simulated server update.
            player=player.copy(position=NavVec(action.pos.x+0.5,action.pos.y+1.0,action.pos.z+0.5),onGround=true)
        }
        fun walk(navigator: CombatNavigator) {
            val yaw=navigator.steeringYaw ?: return
            player=player.copy(yaw=yaw)
            if(keys.none { it in setOf(Key.Type.KEY_W,Key.Type.KEY_A,Key.Type.KEY_S,Key.Type.KEY_D) }) return
            val radians=Math.toRadians(yaw.toDouble())
            var to=NavVec(player.position.x-sin(radians)*0.25,player.position.y,player.position.z+cos(radians)*0.25)
            val geometry=NavigationGeometry(world,0.6,1.8)
            if(navigator.diagnostic.startsWith("drop")) {
                geometry.surfaces(to.x,to.z,to.y,emptyMap(),0.05).firstOrNull { geometry.clear(to.copy(y=it),emptyMap()) }
                    ?.let { to=to.copy(y=it) }
            }
            assertTrue(geometry.walk(player.position,to,emptyMap()) || navigator.diagnostic.startsWith("drop") &&
                geometry.drop(player.position,to,emptyMap()),"unconfirmed support: $to")
            player=player.copy(position=to)
        }
    }
    private fun step(nav: CombatNavigator,tick: Int,target: NavVec,profile: NavigationProfile=NavigationProfile.BEDFIGHT,
                     combat: NavigationCombatState=NavigationCombatState(),sight: Boolean=false)=
        nav.update(tick,target,0.3,sight,true,combat,profile=profile)

    private fun firstSegment(search: IncrementalPathfinder): RouteResult {
        var result=search.compute(maxNodes=1)
        repeat(NavigationTuning.EXISTING_ROUTE_NODES+1) {
            if(result.status==SearchStatus.SEARCHING && result.executableSegment.isEmpty()) result=search.compute(maxNodes=1)
        }
        return result
    }

    @Test fun `bed route height hint starts stairs toward a distant low bed instead of retargeting to the player`() {
        for(distance in listOf(16,32,48)) for(ground in listOf(false,true)) {
            val ctx=Context(World(distance).apply { groundEverywhere=ground })
            val bed=BlockPos(distance,1,0)
            val result=firstSegment(IncrementalPathfinder(ctx.world,ctx.player,
                NavigationGoal(NavVec(distance+0.5,1.5,0.5),4.5,setOf(bed),6.0),ctx.blocks,
                nanoTime={0},profile=NavigationProfile.BEDFIGHT))
            val prefix=result.executableSegment
            assertTrue(prefix.last().position.x>ctx.player.position.x)
            if(ground) assertTrue(prefix.all { it.actions.isEmpty() },"use the existing floor first")
            else {
                assertTrue(prefix.last().position.y>1.0,"distance=$distance $prefix")
                assertEquals(listOf(PlacementStyle.BRIDGE,PlacementStyle.TOWER),prefix.flatMap { it.actions }.map { it.placement })
            }
        }
    }

    @Test fun `higher opponent mid bridge waits for confirmation then replaces flat continuation with stairs`() {
        val ctx=Context(); val nav=CombatNavigator(ctx); val target=NavVec(48.5,1.5,0.5)
        fun update(tick: Int,height: Double?)=nav.update(tick,target,4.5,false,true,
            interactionBlocks=setOf(BlockPos(48,1,0)),profile=NavigationProfile.BEDFIGHT,preferredBridgeHeight=height)
        update(0,null); assertEquals(PlacementStyle.BRIDGE,ctx.requests.single().placement)
        val cancelled=ctx.cancellations
        for(tick in 1..8) update(tick,6.0)
        assertEquals(cancelled,ctx.cancellations); assertEquals(1,ctx.requests.size)
        ctx.confirmAndLand()
        var tick=9
        while(tick<40 && ctx.player.position.y<6.0) {
            update(tick++,6.0)
            if(ctx.status==ActionStatus.WAITING) ctx.confirmAndLand() else ctx.walk(nav)
        }
        assertEquals(6.0,ctx.player.position.y,0.01,nav.diagnostic)
        assertTrue(ctx.requests.any { it.placement==PlacementStyle.TOWER })
        assertTrue(ctx.player.position.x<20.0,"must climb while opponent is still distant")
        repeat(4) {
            update(tick++,6.0)
            if(ctx.status==ActionStatus.WAITING) ctx.confirmAndLand() else ctx.walk(nav)
        }
        assertTrue(ctx.requests.all { it.pos.y<=5 },"do not build above the opponent")
    }

    @Test fun `near bed ignores distant height preference and elevated return can still bridge`() {
        val ctx=Context(World(5).apply { groundEverywhere=true })
        val near=IncrementalPathfinder(ctx.world,ctx.player,
            NavigationGoal(NavVec(5.5,1.5,0.5),4.5,setOf(BlockPos(5,1,0)),8.0),ctx.blocks,
            nanoTime={0},profile=NavigationProfile.BEDFIGHT).compute(maxNodes=1)
        assertTrue(near.executableSegment.all { it.actions.isEmpty() })
        val high=Context()
        high.terrain.placed[BlockPos(0,5,0)]=high.terrain.solid(BlockPos(0,5,0))
        high.player=high.player.copy(position=NavVec(0.5,6.0,0.5))
        val noLowerLand=object : NavigationWorld by high.world {
            override fun block(pos: BlockPos)=if(pos.y==0) NavBlock(0,replaceable=true) else high.world.block(pos)
        }
        val back=firstSegment(IncrementalPathfinder(noLowerLand,high.player,
            NavigationGoal(NavVec(48.5,1.5,0.5),4.5,setOf(BlockPos(48,1,0))),high.blocks,
            nanoTime={0},profile=NavigationProfile.BEDFIGHT))
        assertEquals(PlacementStyle.BRIDGE,back.executableSegment.first().actions.single().placement)
        assertEquals(5,back.executableSegment.first().actions.single().pos.y)
    }

    @Test fun `lost height reference finishes the pending block then resumes a flat bed route`() {
        val ctx=Context(); val nav=CombatNavigator(ctx)
        fun update(tick: Int,height: Double?)=nav.update(tick,NavVec(48.5,1.5,0.5),4.5,false,true,
            interactionBlocks=setOf(BlockPos(48,1,0)),profile=NavigationProfile.BEDFIGHT,preferredBridgeHeight=height)
        update(0,6.0)
        assertEquals(PlacementStyle.BRIDGE,ctx.requests.single().placement)
        val cancelled=ctx.cancellations
        for(tick in 1..8)update(tick,null)
        assertEquals(cancelled,ctx.cancellations); assertEquals(1,ctx.requests.size)
        ctx.confirmAndLand(); update(9,null)
        assertEquals(2,ctx.requests.size)
        assertEquals(PlacementStyle.BRIDGE,ctx.requests.last().placement)
        assertEquals(0,ctx.requests.last().pos.y)
    }

    @Test fun `preferred height obeys ceiling height permissions loaded frontier and available material`() {
        for(mode in 0..4) {
            val ctx=Context(World().apply {
                if(mode==0)ceiling=3
                if(mode==1)heightLimit=0
                if(mode==2)enabled=false
                if(mode==3)loadedThrough=0
            })
            if(mode==4)ctx.blocks=null
            val result=firstSegment(IncrementalPathfinder(ctx.world,ctx.player,
                NavigationGoal(NavVec(48.5,1.5,0.5),4.5,setOf(BlockPos(48,1,0)),8.0),ctx.blocks,
                nanoTime={0},profile=NavigationProfile.BEDFIGHT))
            val actions=result.executableSegment.flatMap { it.actions }
            if(mode<=1) assertTrue(actions.all { it.placement==PlacementStyle.BRIDGE })
            else assertTrue(actions.isEmpty(),"mode=$mode")
        }
    }

    @Test fun `confirmed bridge travel keeps aim ownership and moves backward without sprint`() {
        val ctx=Context(World().apply { groundEverywhere=true }).apply {
            bridgeTravel=true; player=player.copy(yaw=90f)
        }
        val nav=CombatNavigator(ctx)
        step(nav,0,NavVec(48.5,1.0,0.5))
        assertTrue(nav.ownsAim); assertNull(nav.steeringYaw)
        assertTrue(Key.Type.KEY_S in ctx.keys); assertFalse(Key.Type.KEY_LCONTROL in ctx.keys)
        nav.pause(); assertFalse(nav.ownsAim); assertTrue(ctx.keys.isEmpty())
    }

    @Test fun `bounded terrain check then one construction node exposes distant building prefixes`() {
        for(distance in listOf(16,32,48)) for(profile in NavigationProfile.entries) for(bed in listOf(false,true)) {
            val ctx=Context(World(distance))
            val target=NavVec(distance+0.5,1.0,0.5)
            val result=firstSegment(IncrementalPathfinder(ctx.world,ctx.player,
                NavigationGoal(target,if(bed)4.5 else 0.3,if(bed)setOf(BlockPos(distance,1,0))else emptySet()),
                ctx.blocks,nanoTime={0},profile=profile))
            assertEquals(SearchStatus.SEARCHING,result.status)
            assertTrue(result.executableSegment.size in 1..3)
            assertEquals(BlockPos(1,0,0),result.executableSegment.first().actions.single().pos)
            assertEquals(PlacementStyle.BRIDGE,result.executableSegment.first().actions.single().placement)
        }
    }

    @Test fun `long bridges roll beyond twelve edits and thirty two blocks without a nearby player`() {
        for(distance in listOf(16,32,48)) {
            val ctx=Context(World(distance)); val nav=CombatNavigator(ctx); val target=NavVec(distance+0.5,1.0,0.5)
            var first=-1; var tick=0
            while(tick<500 && ctx.player.position.x<distance) {
                step(nav,++tick,target)
                if(ctx.status==ActionStatus.WAITING) {
                    if(first<0)first=tick
                    assertEquals(PlacementStyle.BRIDGE,ctx.requests.last().placement)
                    val original=ctx.player.position
                    repeat(3) { step(nav,++tick,target.copy(z=0.5+it*0.02)); assertEquals(original,ctx.player.position) }
                    ctx.confirmAndLand()
                } else ctx.walk(nav)
            }
            assertTrue(first in 1..10,"distance=$distance first=$first")
            assertTrue(ctx.player.position.x>=distance,"distance=$distance ${nav.diagnostic} at ${ctx.player.position}")
            assertEquals(distance-1,ctx.requests.size)
            assertTrue(nav.searchesStarted>1)
            assertTrue(ctx.requests.zipWithNext().all { (a,b) -> b.pos.x==a.pos.x+1 && b.pos.z==a.pos.z })
        }
    }

    @Test fun `search radius and terrain batches return continuation rather than success`() {
        for(terrain in listOf(World(60),World(60).apply { groundEverywhere=true })) {
            val ctx=Context(terrain)
            val search=IncrementalPathfinder(terrain,ctx.player,NavigationGoal(NavVec(60.5,1.0,0.5),0.3),ctx.blocks,nanoTime={0},profile=NavigationProfile.BEDFIGHT)
            var result=search.compute()
            while(result.status==SearchStatus.SEARCHING)result=search.compute()
            assertEquals(SearchStatus.PARTIAL,result.status)
            assertTrue(result.path.isNotEmpty())
            assertTrue(result.path.flatMap { it.actions }.size<=12)
            assertTrue(result.path.last().position.x<60)
            assertTrue(result.path.all { hypot(it.position.x-0.5,it.position.z-0.5)<=32 })
        }
    }

    @Test fun `elevated offset goals build advancing stairs and void foundations before towers`() {
        for(ground in listOf(false,true)) {
            val ctx=Context(World(8).apply { groundEverywhere=ground })
            val search=IncrementalPathfinder(ctx.world,ctx.player,NavigationGoal(NavVec(8.5,6.0,0.5),0.3),ctx.blocks,nanoTime={0},profile=NavigationProfile.BEDFIGHT)
            val prefix=firstSegment(search).executableSegment
            assertTrue(prefix.isNotEmpty())
            assertTrue(prefix.last().position.x>0.5)
            if(ground) {
                assertTrue(prefix.all { it.actions.isEmpty() },"walk existing ground before constructing")
            } else {
                assertEquals(listOf(PlacementStyle.BRIDGE,PlacementStyle.TOWER),prefix.flatMap { it.actions }.map { it.placement })
                assertEquals(prefix[0].position.x,prefix[1].position.x)
            }
        }
        val ctx=Context(World(8)); val nav=CombatNavigator(ctx); var tick=0
        while(ctx.player.position.y<5 && tick<80) {
            step(nav,++tick,NavVec(8.5,6.0,0.5))
            if(ctx.status==ActionStatus.WAITING)ctx.confirmAndLand() else ctx.walk(nav)
        }
        assertTrue(ctx.player.position.y>=5,nav.diagnostic)
        assertTrue(ctx.player.position.x>2.5,"stairs should advance horizontally")
        assertEquals(PlacementStyle.BRIDGE,ctx.requests.first().placement)
        assertTrue(ctx.requests.zipWithNext().all { (a,b) -> b.pos.x>=a.pos.x })
    }

    @Test fun `overhead tower respects ceiling height cap material and loaded frontier`() {
        val ctx=Context(World(8).apply { groundEverywhere=true }); val nav=CombatNavigator(ctx)
        step(nav,1,NavVec(0.5,8.0,0.5))
        assertEquals(PlacementStyle.TOWER,ctx.requests.single().placement)
        for(mode in 0..4) {
            val blocked=Context(World().apply {
                if(mode==0)ceiling=3
                if(mode==1)heightLimit=-1
                if(mode==2)enabled=false
                if(mode==3)loadedThrough=0
            })
            if(mode==4)blocked.blocks=null
            val n=CombatNavigator(blocked)
            repeat(50) { step(n,it,NavVec(48.5,if(mode==0)8.0 else 1.0,0.5))
                assertFalse(Key.Type.KEY_SPACE in blocked.keys)
                assertTrue(blocked.keys.none { key -> key in setOf(Key.Type.KEY_A,Key.Type.KEY_D) })
            }
            // A low ceiling still permits a flat bridge; it must never produce a tower.
            if(mode==0) assertTrue(blocked.requests.all { it.placement==PlacementStyle.BRIDGE })
            else assertTrue(blocked.requests.isEmpty(),"mode=$mode")
        }
    }

    @Test fun `BedFight builds over legal gaps while ordinary jumps and fallback jumps remain`() {
        val ctx=Context(World(3))
        fun first(profile: NavigationProfile)=firstSegment(IncrementalPathfinder(ctx.world,ctx.player,NavigationGoal(NavVec(6.5,1.0,0.5),0.3),ctx.blocks,
            nanoTime={0},profile=profile)).executableSegment.first()
        assertEquals(Traversal.JUMP,first(NavigationProfile.DEFAULT).traversal)
        assertEquals(PlacementStyle.BRIDGE,first(NavigationProfile.BEDFIGHT).actions.single().placement)
        ctx.blocks=null
        assertEquals(Traversal.JUMP,first(NavigationProfile.BEDFIGHT).traversal)
        ctx.terrain.groundEverywhere=true
        ctx.terrain.placed[BlockPos(1,1,0)]=ctx.terrain.solid(BlockPos(1,1,0)); ctx.terrain.revision++
        val raised=IncrementalPathfinder(ctx.world,ctx.player,NavigationGoal(NavVec(1.5,2.0,0.5),0.3),nanoTime={0},profile=NavigationProfile.BEDFIGHT).compute()
        assertEquals(SearchStatus.SUCCESS,raised.status)
        assertTrue(raised.path.any { it.traversal==Traversal.JUMP && it.actions.isEmpty() })
    }

    @Test fun `a failed jump is bounded and does not prohibit bridging the same gap`() {
        val ctx=Context(World(3)); ctx.blocks=null; val nav=CombatNavigator(ctx)
        var launches=0
        for(tick in 0..30) {
            step(nav,tick,NavVec(6.5,1.0,0.5),NavigationProfile.DEFAULT)
            if(Key.Type.KEY_SPACE in ctx.keys)launches++
        }
        assertEquals(1,launches,"must not repeatedly take off without landing")
        ctx.blocks=BuildingMaterial(35,0,2,8)
        var tick=31
        while(ctx.requests.isEmpty() && tick<45)step(nav,tick++,NavVec(6.5,1.0,0.5),NavigationProfile.DEFAULT)
        assertEquals(PlacementStyle.BRIDGE,ctx.requests.first().placement)
        assertEquals(BlockPos(1,0,0),ctx.requests.first().pos)
    }

    @Test fun `jump controller allows one takeoff and times out alignment and flight in twenty four ticks`() {
        val from=NavVec(0.5,1.0,0.5); val to=NavVec(3.5,1.0,0.5)
        val state=NavigationState(from,-90f,true,false,300)
        val alignment=JumpTraversal(from,to,0)
        for(tick in 0..23)assertEquals(JumpTraversal.Phase.PREPARING,alignment.update(tick,state,false))
        assertEquals(JumpTraversal.Phase.FAILED,alignment.update(24,state,false))
        val flight=JumpTraversal(from,to,0)
        assertEquals(JumpTraversal.Phase.TAKEOFF,flight.update(0,state,true))
        for(tick in 1..23)assertEquals(JumpTraversal.Phase.AIRBORNE,flight.update(tick,state.copy(onGround=false),true))
        assertEquals(JumpTraversal.Phase.FAILED,flight.update(24,state.copy(onGround=false),true))
        val landing=JumpTraversal(from,to,0)
        landing.update(0,state,true); landing.update(1,state.copy(onGround=false),true)
        assertEquals(JumpTraversal.Phase.LANDED,landing.update(10,state.copy(position=to),true))
    }

    @Test fun `visible protected opponent across a gap still needs construction`() {
        val ctx=Context(World(3)); val nav=CombatNavigator(ctx)
        val combat=NavigationCombatState(protected=true,preserveOpenMovement=true)
        for(tick in 1..4)step(nav,tick,NavVec(3.5,1.0,0.5),combat=combat,sight=true)
        assertTrue(nav.ownsMovement); assertEquals(PlacementStyle.BRIDGE,ctx.requests.single().placement)
        ctx.terrain.groundEverywhere=true; ctx.terrain.revision++
        assertFalse(step(nav,5,NavVec(3.5,1.0,0.5),combat=combat,sight=true))
        assertEquals(ActionStatus.IDLE,ctx.status)
    }

    @Test fun `small target changes keep pending work but direction reversal and lost targets cancel`() {
        val ctx=Context(); val nav=CombatNavigator(ctx)
        step(nav,0,NavVec(48.5,1.0,0.5)); assertEquals(1,ctx.requests.size)
        val cancellations=ctx.cancellations
        for(tick in 1..12)step(nav,tick,NavVec(48.5,1.0,0.5+sin(tick.toDouble())*0.5))
        assertEquals(1,ctx.requests.size); assertEquals(cancellations,ctx.cancellations)
        step(nav,13,NavVec(-10.5,1.0,0.5))
        assertEquals(ActionStatus.IDLE,ctx.status)
        nav.update(14,null,0.3,false,true,profile=NavigationProfile.BEDFIGHT)
        assertFalse(nav.ownsMovement); assertTrue(ctx.keys.isEmpty())
    }

    @Test fun `inventory exhaustion rejection and lost support wait safely at the frontier`() {
        for(mode in 0..2) {
            val ctx=Context(); val nav=CombatNavigator(ctx)
            ctx.terrain.laneOnly=true
            step(nav,0,NavVec(48.5,1.0,0.5)); assertEquals(1,ctx.requests.size)
            if(mode==0) { ctx.blocks=ctx.blocks!!.copy(count=1); ctx.confirmAndLand() }
            else ctx.status=ActionStatus.FAILED
            if(mode==2) {
                ctx.terrain.placed[BlockPos(0,0,0)]=NavBlock(0,replaceable=true)
                ctx.terrain.enabled=false; ctx.terrain.revision++
            }
            val original=ctx.player.position
            repeat(35) { step(nav,it+1,NavVec(48.5,1.0,0.5)); assertFalse(Key.Type.KEY_SPACE in ctx.keys) }
            assertEquals(1,ctx.requests.size,"mode=$mode")
            assertEquals(original,ctx.player.position)
            assertTrue(ctx.keys.none { it in setOf(Key.Type.KEY_W,Key.Type.KEY_A,Key.Type.KEY_S,Key.Type.KEY_D) })
        }
    }

    @Test fun `lateral oscillation cannot indefinitely refresh segment progress`() {
        val ctx=Context(World().apply {
            groundEverywhere=true; enabled=false
            for(z in -2..2) for(y in 1..3)placed[BlockPos(3,y,z)]=solid(BlockPos(3,y,z))
        })
        val nav=CombatNavigator(ctx)
        for(tick in 0..45) {
            ctx.player=ctx.player.copy(position=NavVec(0.5,1.0,if(tick%2==0)0.25 else 0.75))
            step(nav,tick,NavVec(8.5,1.0,0.5))
        }
        assertTrue(nav.searchesStarted>=2,"sideways movement should trigger recovery: ${nav.searchesStarted}")
        assertTrue(ctx.requests.isEmpty())
    }

    @Test fun `shared budgets eventually release construction for one ten and fifty bots`() {
        for(count in listOf(1,10,50)) {
            var now=0L; val budget=SharedNavigationBudget({now})
            val contexts=List(count) { Context().apply { searchBudget=budget } }
            val navs=contexts.map { CombatNavigator(it) }
            for(tick in 0..39) {
                navs.forEachIndexed { i,n -> if(contexts[i].requests.isEmpty())step(n,tick,NavVec(48.5,1.0,0.5)) }
                now+=50_000_000
                if(contexts.all { it.requests.isNotEmpty() })break
            }
            assertTrue(contexts.all { it.requests.isNotEmpty() },"count=$count")
            navs.forEach { it.pause() }
        }
    }

    @Test fun `continuous movement during budget waits is not mistaken for teleportation`() {
        val ctx=Context(World().apply {
            groundEverywhere=true
            for(z in -4..4)for(y in 1..3)placed[BlockPos(15,y,z)]=solid(BlockPos(15,y,z))
        })
        ctx.searchBudget=object : NavigationSearchBudget { override fun acquire(owner: Any)=0L }
        val nav=CombatNavigator(ctx)
        for(tick in 0..25) {
            ctx.player=ctx.player.copy(position=NavVec(0.5+tick*0.2,1.0,0.5))
            step(nav,tick,NavVec(48.5,1.0,0.5))
        }
        assertEquals(1,nav.searchesStarted)
        assertTrue(Key.Type.KEY_W in ctx.keys)
    }
}
