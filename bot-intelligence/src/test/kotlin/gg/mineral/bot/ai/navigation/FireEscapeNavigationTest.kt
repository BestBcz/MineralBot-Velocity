package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class FireEscapeNavigationTest {
    private fun FixtureWorld.fire(x: Int, z: Int, y: Int = 1) {
        blocks[BlockPos(x,y,z)] = NavBlock(51,hazardous=true,replaceable=true)
    }

    private class Context : NavigationContext {
        override val world = FixtureWorld()
        override var searchBudget = object : NavigationSearchBudget {
            override fun acquire(owner: Any) = Long.MAX_VALUE
        }
        var player = NavigationState(NavVec(0.5,1.0,0.5),-90f,true,false,300)
        var keys = emptySet<Key.Type>()
        var status = ActionStatus.IDLE
        var cancellations = 0
        val requests = mutableListOf<BlockAction>()
        override fun state() = player
        override fun material() = BuildingMaterial(35,0,2,64)
        override fun move(keys: Set<Key.Type>) { this.keys = keys }
        override fun requestAction(action: BlockAction): ActionStatus {
            requests.add(action); status=ActionStatus.WAITING; return status
        }
        override fun actionStatus() = status
        override fun tickAction() {}
        override fun cancelAction() { cancellations++; keys=emptySet(); status=ActionStatus.IDLE }
        override fun reset() = cancelAction()

        // Exercise actual movement keys with a fixed view, as when another goal owns aim.
        fun walkTick() {
            val forward = (if(Key.Type.KEY_W in keys) 1 else 0) - (if(Key.Type.KEY_S in keys) 1 else 0)
            val strafe = (if(Key.Type.KEY_A in keys) 1 else 0) - (if(Key.Type.KEY_D in keys) 1 else 0)
            val length = hypot(forward.toDouble(),strafe.toDouble())
            if(length == 0.0) return
            val yaw = Math.toRadians(player.yaw.toDouble())
            val next = player.position.copy(
                x=player.position.x+(-sin(yaw)*forward+cos(yaw)*strafe)/length*0.16,
                z=player.position.z+(cos(yaw)*forward+sin(yaw)*strafe)/length*0.16)
            assertTrue(NavigationGeometry(world,0.6,1.8,ignoreFire=true).walk(player.position,next,emptyMap()),
                "movement must retain collision and footing checks: $next")
            player=player.copy(position=next)
        }
    }

    private fun solve(world: NavigationWorld, escape: Boolean, start: NavVec = NavVec(0.5,1.0,0.5),
                      target: NavVec = NavVec(7.5,1.0,0.5)): RouteResult {
        val search = IncrementalPathfinder(world,NavigationState(start,0f,true,false,300),
            NavigationGoal(target,0.3),BuildingMaterial(35,0,2,64),nanoTime={0},escapeFire=escape)
        var result = search.compute()
        repeat(140) { if(result.status==SearchStatus.SEARCHING) result=search.compute() }
        return result
    }

    @Test fun `ordinary search still avoids fire instead of treating it as air`() {
        val world=FixtureWorld()
        for(z in -1..1) world.fire(3,z)
        val result=solve(world,false)
        assertEquals(SearchStatus.SUCCESS,result.status)
        val geometry=NavigationGeometry(world,0.6,1.8)
        var previous=NavVec(0.5,1.0,0.5)
        for(step in result.path) {
            assertTrue(geometry.walk(previous,step.position,emptyMap()))
            assertFalse(geometry.touchingFire(step.position))
            previous=step.position
        }
    }

    @Test fun `escape finds the cheapest exit even when it leads away from the enemy`() {
        val world=FixtureWorld().apply { building=true }
        for(x in 0..4) world.fire(x,0)
        for(x in -2..5) for(y in 1..3) {
            world.solid(x,y,-1); world.solid(x,y,1)
        }
        val result=solve(world,true)
        assertEquals(SearchStatus.SUCCESS,result.status,result.toString())
        assertEquals(NavVec(-0.5,1.0,0.5),result.path.last().position)
        assertTrue(result.path.all { it.actions.isEmpty() })
        assertTrue(world.block(BlockPos(0,1,0))!!.hazardous,"search must not modify the world")
    }

    @Test fun `single and multiple fire blocks produce continuous movement and return combat control`() {
        for(radius in listOf(0,2)) {
            val ctx=Context()
            for(x in -radius..radius) for(z in -radius..radius) ctx.world.fire(x,z)
            val nav=CombatNavigator(ctx)
            val geometry=NavigationGeometry(ctx.world,0.6,1.8)
            var tick=0
            while(geometry.touchingFire(ctx.player.position) && tick<80) {
                assertTrue(nav.update(++tick,NavVec(7.5,1.0,0.5),3.0,true,true,
                    NavigationCombatState(protected=true,preserveOpenMovement=true)))
                assertTrue(ctx.keys.any { it in setOf(Key.Type.KEY_W,Key.Type.KEY_A,Key.Type.KEY_S,Key.Type.KEY_D) },
                    "radius=$radius tick=$tick ${nav.diagnostic}")
                ctx.walkTick()
            }
            assertFalse(geometry.touchingFire(ctx.player.position),"radius=$radius ${nav.diagnostic}")
            assertTrue(geometry.safeFooting(ctx.player.position))
            assertTrue(ctx.requests.isEmpty())
            // Ongoing burn damage is irrelevant once there is no fire at the body.
            assertFalse(nav.update(++tick,ctx.player.position.copy(y=1.0),3.0,true,true,
                NavigationCombatState(protected=true,preserveOpenMovement=true)))
            assertEquals("combat",nav.diagnostic)
        }
    }

    @Test fun `body overlap at a fire boundary triggers escape without an enemy`() {
        val ctx=Context()
        ctx.world.fire(0,0)
        ctx.player=ctx.player.copy(position=NavVec(1.2,1.0,0.5))
        assertNotEquals(51,ctx.world.block(ctx.player.position.block)!!.id)
        val nav=CombatNavigator(ctx)
        assertTrue(nav.update(1,null,3.0,false,false))
        assertTrue(ctx.keys.isNotEmpty(),nav.diagnostic)
        for(tick in 2..20) {
            ctx.walkTick()
            nav.update(tick,null,3.0,false,false)
            if(nav.diagnostic=="idle") break
        }
        assertEquals("idle",nav.diagnostic)
        assertTrue(ctx.keys.isEmpty())
        assertFalse(NavigationGeometry(ctx.world,0.6,1.8).touchingFire(ctx.player.position))
    }

    @Test fun `escape respects the shared search budget and resumes when it is available`() {
        val ctx=Context()
        ctx.world.fire(0,0)
        ctx.searchBudget=object : NavigationSearchBudget { override fun acquire(owner: Any)=0L }
        val nav=CombatNavigator(ctx)
        nav.update(1,null,3.0,false,false)
        assertTrue(ctx.keys.isEmpty(),"do not move deeper into fire without a validated exit")
        ctx.searchBudget=NavigationSearchBudget.UNLIMITED
        for(tick in 2..25) {
            nav.update(tick,null,3.0,false,false)
            if(ctx.keys.isNotEmpty()) break
        }
        assertTrue(ctx.keys.isNotEmpty(),nav.diagnostic)
    }

    @Test fun `fire cancels a pending terrain action before escaping`() {
        val ctx=Context()
        ctx.world.building=true
        for(x in -1..1) for(z in -1..1) if(x!=0 || z!=0) for(y in 1..3) ctx.world.solid(x,y,z)
        ctx.world.breakable.add(BlockPos(1,1,0)); ctx.world.breakable.add(BlockPos(1,2,0))
        val nav=CombatNavigator(ctx)
        nav.update(1,NavVec(7.5,1.0,0.5),0.3,false,true)
        assertTrue(nav.ownsInteraction)
        val before=ctx.cancellations
        ctx.world.fire(0,0)
        for(y in 1..3) ctx.world.blocks.remove(BlockPos(-1,y,0))
        ctx.world.revision++
        nav.update(2,NavVec(7.5,1.0,0.5),0.3,false,true)
        assertFalse(nav.ownsInteraction)
        assertTrue(ctx.cancellations>before)
        assertEquals(1,ctx.requests.size)
        assertTrue(ctx.keys.isNotEmpty(),nav.diagnostic)
    }

    @Test fun `escape bypasses an existing retry delay and replans an exit that catches fire`() {
        val ctx=Context()
        for(x in -1..1) for(z in -1..1) if(x!=0 || z!=0) for(y in 1..3) ctx.world.solid(x,y,z)
        val nav=CombatNavigator(ctx)
        nav.update(1,NavVec(7.5,1.0,0.5),0.3,false,false)
        assertTrue(ctx.keys.isEmpty())
        ctx.world.fire(0,0)
        for(y in 1..3) ctx.world.blocks.remove(BlockPos(-1,y,0))
        // Deliberately retain the revision to isolate clearing the old retry delay on entry.
        nav.update(2,NavVec(7.5,1.0,0.5),0.3,false,false)
        assertTrue(ctx.keys.isNotEmpty(),nav.diagnostic)
        assertEquals(90f,nav.steeringYaw)
        ctx.world.fire(-1,0)
        for(y in 1..3) ctx.world.blocks.remove(BlockPos(0,y,-1))
        ctx.world.revision++
        nav.update(3,NavVec(7.5,1.0,0.5),0.3,false,false)
        assertEquals(-180f,nav.steeringYaw)
        assertTrue(ctx.requests.isEmpty())
    }

    @Test fun `fire exemption never permits lava cactus walls void or unloaded exits`() {
        for(blocker in listOf(1,10,11,81,-1,-2)) {
            val world=FixtureWorld()
            world.fire(0,0)
            for(x in -1..1) for(z in -1..1) if(x!=0 || z!=0) {
                when(blocker) {
                    1 -> for(y in 1..3) world.solid(x,y,z)
                    -1 -> world.gaps.add(x to z)
                    -2 -> Unit
                    else -> world.blocks[BlockPos(x,1,z)]=NavBlock(blocker,hazardous=true)
                }
            }
            val terrain=if(blocker == -2) object : NavigationWorld by world {
                override fun block(pos: BlockPos): NavBlock? =
                    if(pos.x!=0 || pos.z!=0) null else world.block(pos)
            } else world
            // Eliminate gap-jump landings beyond the immediately surrounding void.
            if(blocker == -1) for(x in -40..40) for(z in -40..40) if(x!=0 || z!=0) world.gaps.add(x to z)
            val result=solve(terrain,true)
            assertEquals(SearchStatus.NO_PATH,result.status,"blocker=$blocker $result")
            assertTrue(result.path.isEmpty())
        }
    }
}
