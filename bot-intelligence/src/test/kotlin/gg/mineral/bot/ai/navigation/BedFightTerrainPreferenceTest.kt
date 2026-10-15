package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class BedFightTerrainPreferenceTest {
    private class Context(override val world: NavigationWorld) : NavigationContext {
        var player=NavigationState(NavVec(0.5,1.0,0.5),-90f,true,false,300)
        override var searchBudget=object : NavigationSearchBudget { override fun acquire(owner: Any)=Long.MAX_VALUE }
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

    @Test fun `join a bridge one or two blocks to either side instead of building a parallel bridge`() {
        for(offset in listOf(-2,-1,1,2)) {
            val world=object : NavigationWorld {
                override val revision=0L
                override fun canBreak(pos: BlockPos,block: NavBlock)=false
                override fun canPlace(pos: BlockPos)=true
                override fun block(pos: BlockPos): NavBlock {
                    val solid=pos.y==0 && (pos.x<=0 && abs(pos.z)<=3 || pos.z==offset || pos.x>=12)
                    return if(solid) NavBlock(1,boxes=listOf(NavBox(pos.x.toDouble(),0.0,pos.z.toDouble(),pos.x+1.0,1.0,pos.z+1.0)))
                        else NavBlock(0,replaceable=true)
                }
            }
            val ctx=Context(world); val nav=CombatNavigator(ctx); var joined=false
            var tick=0
            while(tick<150 && ctx.player.position.x<12.0) {
                nav.update(tick++,NavVec(13.5,1.0,0.5),0.3,false,true,profile=NavigationProfile.BEDFIGHT)
                assertTrue(ctx.requests.isEmpty(),"offset=$offset $tick ${ctx.requests}")
                assertFalse(Key.Type.KEY_LSHIFT in ctx.keys,"must walk the existing bridge")
                val yaw=nav.steeringYaw ?: continue
                ctx.player=ctx.player.copy(yaw=yaw)
                if(ctx.keys.none { it in setOf(Key.Type.KEY_W,Key.Type.KEY_A,Key.Type.KEY_S,Key.Type.KEY_D) }) continue
                val angle=Math.toRadians(yaw.toDouble())
                val next=ctx.player.position.copy(x=ctx.player.position.x-sin(angle)*0.2,z=ctx.player.position.z+cos(angle)*0.2)
                assertTrue(NavigationGeometry(world,0.6,1.8).walk(ctx.player.position,next,emptyMap()),"offset=$offset $next")
                ctx.player=ctx.player.copy(position=next)
                if(abs(next.z-offset-0.5)<0.3)joined=true
            }
            assertTrue(joined,"offset=$offset did not use bridge")
            assertTrue(ctx.player.position.x>=12.0,"offset=$offset ${ctx.player.position} ${nav.diagnostic}")
        }
    }

    @Test fun `stairs slabs and two successive one block rises use existing terrain with blocks available`() {
        for(stairs in listOf(false,true)) {
            val world=FixtureWorld().apply { building=true }
            if(stairs) {
                world.blocks[BlockPos(1,1,0)]=NavBlock(53,boxes=listOf(
                    NavBox(1.0,1.0,0.0,2.0,1.5,1.0),NavBox(1.5,1.5,0.0,2.0,2.0,1.0)))
                world.solid(2,1,0)
                world.blocks[BlockPos(3,2,0)]=NavBlock(44,boxes=listOf(NavBox(3.0,2.0,0.0,4.0,2.5,1.0)))
                world.solid(3,1,0)
            } else {
                world.solid(1,1,0)
                for(x in 2..3)for(y in 1..2)world.solid(x,y,0)
            }
            val target=NavVec(3.5,if(stairs)2.5 else 3.0,0.5)
            var state=NavigationState(NavVec(0.5,1.0,0.5),-90f,true,false,300)
            val traversals=mutableListOf<Traversal>()
            var reached=false
            repeat(15) {
                if(!reached) {
                    val search=IncrementalPathfinder(world,state,NavigationGoal(target,0.3),BuildingMaterial(35,0,2,64),
                        nanoTime={0},profile=NavigationProfile.BEDFIGHT)
                    var result=search.compute()
                    repeat(140) { if(result.status==SearchStatus.SEARCHING)result=search.compute() }
                    assertTrue(result.path.isNotEmpty(),result.toString())
                    assertTrue(result.path.all { it.actions.isEmpty() },"stairs=$stairs ${result.path}")
                    traversals.addAll(result.path.map { it.traversal })
                    state=state.copy(position=result.path.last().position)
                    reached=result.status==SearchStatus.SUCCESS
                }
            }
            assertTrue(reached,"stairs=$stairs at ${state.position}")
            assertEquals(target.y,state.position.y,0.01)
            assertTrue((if(stairs)Traversal.STEP else Traversal.JUMP) in traversals)
        }
    }

    @Test fun `search waits stand normally on safe ground preserve view and only sneak at a dangerous edge`() {
        for(edge in listOf(false,true)) {
            val world=FixtureWorld().apply {
                building=true
                if(edge) for(x in 1..40)for(z in -40..40)gaps.add(x to z)
                else for(y in 1..3)solid(1,y,0)
            }
            val ctx=Context(world)
            ctx.searchBudget=object : NavigationSearchBudget { override fun acquire(owner: Any)=0L }
            ctx.player=ctx.player.copy(position=NavVec(if(edge)0.95 else 0.5,1.0,0.5))
            val nav=CombatNavigator(ctx)
            repeat(8) {
                nav.update(it,NavVec(20.5,1.0,0.5),0.3,false,true,profile=NavigationProfile.BEDFIGHT)
                assertEquals(edge,Key.Type.KEY_LSHIFT in ctx.keys)
                assertNull(nav.steeringYaw,"waiting must not turn the bot toward the enemy")
                assertTrue(ctx.keys.all { key -> key==Key.Type.KEY_LSHIFT })
                assertTrue(ctx.requests.isEmpty())
            }
        }
    }
}
