package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class DescentNavigationTest {
    private class World(val top: (Int)->Double) : NavigationWorld {
        override var revision=0L
        var void=false
        var unloaded: Int?=null
        var fluid=false
        var lava=false
        var obstruction=false
        override fun block(pos: BlockPos): NavBlock? {
            if(pos.y !in 0..255 || pos.x>0 && pos.y==unloaded) return null
            val height=if(pos.x<=0)top(pos.x) else if(void)0.0 else top(pos.x)
            if(pos.x>0 && pos.y==0 && (fluid || lava)) return NavBlock(if(lava)10 else 8,water=fluid,hazardous=lava,replaceable=true)
            if(obstruction && pos.x==1 && pos.y==10) return solid(pos,11.0)
            return if(pos.y<height) solid(pos,min(pos.y+1.0,height)) else NavBlock(0,replaceable=true)
        }
        private fun solid(p: BlockPos,top: Double)=NavBlock(1,boxes=listOf(NavBox(p.x.toDouble(),p.y.toDouble(),p.z.toDouble(),p.x+1.0,top,p.z+1.0)))
        override fun canPlace(pos: BlockPos)=true
        override fun canBreak(pos: BlockPos,block: NavBlock)=false
    }
    private class Context(override val world: World,var player: NavigationState) : NavigationContext {
        override val searchBudget=object : NavigationSearchBudget { override fun acquire(owner: Any)=Long.MAX_VALUE }
        var keys=emptySet<Key.Type>()
        val requests=mutableListOf<BlockAction>()
        override fun state()=player
        override fun material()=BuildingMaterial(35,0,2,64)
        override fun move(keys: Set<Key.Type>) { this.keys=keys }
        override fun requestAction(action: BlockAction): ActionStatus { requests.add(action); return ActionStatus.WAITING }
        override fun actionStatus()=if(requests.isEmpty()) ActionStatus.IDLE else ActionStatus.WAITING
        override fun tickAction() {}
        override fun cancelAction() { keys=emptySet() }
        override fun reset()=cancelAction()
    }
    private fun state(x: Double,y: Double,ground: Boolean=true,vx: Double=0.0)=
        NavigationState(NavVec(x,y,0.5),-90f,ground,false,300,velocity=NavVec(vx,0.0,0.0))

    @Test fun `descending half and full blocks never places crouches or turns around when the center is overshot`() {
        val world=World { x -> when { x<=0 -> 6.0; x==1 -> 5.5; x==2 -> 4.5; else -> 3.5 } }
        val ctx=Context(world,state(0.5,6.0)); val nav=CombatNavigator(ctx); var tick=0
        fun update() {
            nav.update(tick++,NavVec(6.5,3.5,0.5),0.3,false,true,profile=NavigationProfile.BEDFIGHT)
            assertTrue(ctx.requests.isEmpty())
            assertFalse(Key.Type.KEY_LSHIFT in ctx.keys); assertFalse(Key.Type.KEY_SPACE in ctx.keys)
            nav.steeringYaw?.let { assertEquals(-90f,it,0.1f) }
        }
        for((x,height) in listOf(1.5 to 5.5,2.5 to 4.5,3.5 to 3.5)) {
            update()
            ctx.player=state(x+0.25,height+0.4,false,0.2)
            update()
            assertEquals(-90f,nav.steeringYaw!!,0.1f)
            assertTrue(Key.Type.KEY_S in ctx.keys,"brake using input rather than turning the view")
            ctx.player=state(x+0.1,height)
            update()
        }
    }

    @Test fun `BedFight can descend thirty nine blocks onto verified land and replans after landing`() {
        val world=World { if(it<=0)40.0 else 1.0 }
        val ctx=Context(world,state(0.5,40.0)); val nav=CombatNavigator(ctx)
        nav.update(0,NavVec(4.5,1.0,0.5),0.3,false,true,profile=NavigationProfile.BEDFIGHT)
        assertTrue(nav.diagnostic.startsWith("drop"),nav.diagnostic)
        for((tick,height) in listOf(1 to 30.0,2 to 15.0,3 to 2.0)) {
            ctx.player=state(1.6,height,false,0.1)
            nav.update(tick,NavVec(4.5,1.0,0.5),0.3,false,true,profile=NavigationProfile.BEDFIGHT)
            assertEquals(-90f,nav.steeringYaw!!,0.1f)
            assertFalse(Key.Type.KEY_LSHIFT in ctx.keys); assertFalse(Key.Type.KEY_SPACE in ctx.keys)
            assertTrue(ctx.requests.isEmpty())
        }
        ctx.player=state(1.6,1.0)
        nav.update(4,NavVec(4.5,1.0,0.5),0.3,false,true,profile=NavigationProfile.BEDFIGHT)
        nav.update(5,NavVec(4.5,1.0,0.5),0.3,false,true,profile=NavigationProfile.BEDFIGHT)
        assertTrue(Key.Type.KEY_W in ctx.keys)
        assertTrue(ctx.requests.isEmpty())
    }

    @Test fun `void unknown columns liquids and obstructed falls are not deep landing routes`() {
        for(mode in 0..4) {
            val world=World { if(it<=0)40.0 else 1.0 }.apply {
                if(mode==0)void=true
                if(mode==1)unloaded=20
                if(mode==2)fluid=true
                if(mode==3)lava=true
                if(mode==4)obstruction=true
            }
            val geometry=NavigationGeometry(world,0.6,1.8)
            val landing=geometry.landingBelow(1.5,0.5,40.0,emptyMap())
            if(mode<4) assertNull(landing,"mode=$mode")
            else {
                assertEquals(11.0,landing!!.y)
                assertFalse(geometry.drop(NavVec(0.5,40.0,0.5),NavVec(1.5,1.0,0.5),emptyMap()))
            }
        }
    }

    @Test fun `deep falling permission belongs to BedFight and a missed landing is bounded`() {
        val world=World { if(it<=0)40.0 else 1.0 }
        val ordinary=IncrementalPathfinder(world,state(0.5,40.0),NavigationGoal(NavVec(4.5,1.0,0.5),0.3),
            nanoTime={0},profile=NavigationProfile.DEFAULT).compute(maxNodes=1)
        assertTrue(ordinary.executableSegment.none { it.traversal==Traversal.DROP })
        val attempt=DropTraversal(NavVec(0.5,40.0,0.5),NavVec(1.5,1.0,0.5),0)
        assertEquals(DropTraversal.Phase.FALLING,attempt.update(1,state(1.8,20.0,false)))
        assertEquals(-90f,attempt.yaw,0.1f)
        assertEquals(DropTraversal.Phase.FAILED,attempt.update(151,state(1.8,5.0,false)))
    }

    @Test fun `deep drops wait out strong horizontal knockback and losing the landing stops steering`() {
        val world=World { if(it<=0)40.0 else 1.0 }
        val unstable=IncrementalPathfinder(world,state(0.5,40.0,vx=0.8),NavigationGoal(NavVec(4.5,1.0,0.5),0.3),
            nanoTime={0},profile=NavigationProfile.BEDFIGHT).compute(maxNodes=1)
        assertTrue(unstable.executableSegment.none { it.traversal==Traversal.DROP })
        val ctx=Context(world,state(0.5,40.0)); val nav=CombatNavigator(ctx)
        nav.update(0,NavVec(4.5,1.0,0.5),0.3,false,true,profile=NavigationProfile.BEDFIGHT)
        assertTrue(nav.diagnostic.startsWith("drop"))
        world.void=true; world.revision++
        ctx.player=state(1.5,20.0,false)
        nav.update(1,NavVec(4.5,1.0,0.5),0.3,false,true,profile=NavigationProfile.BEDFIGHT)
        assertTrue(ctx.keys.isEmpty()); assertTrue(ctx.requests.isEmpty()); assertNull(nav.steeringYaw)
    }
}
