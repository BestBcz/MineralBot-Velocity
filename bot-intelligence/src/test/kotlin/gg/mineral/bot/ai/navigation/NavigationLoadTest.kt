package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Measures sliced search only, not live Minecraft/server throughput. */
class NavigationLoadTest {
    @Test fun `shared budget gives one ten and fifty bots prompt movement and a first safe route`() {
        for(count in listOf(1,10,50)) {
            var now=0L
            val budget=SharedNavigationBudget({ now })
            val navigators=List(count) {
                val world=FixtureWorld()
                for(z in -2..2)for(y in 1..3)world.solid(3,y,z)
                val context=object : NavigationContext {
                    override val world=world
                    override val searchBudget=budget
                    override fun state()=NavigationState(NavVec(0.5,1.0,0.5),-90f,true,false,300)
                    override fun material(): BuildingMaterial?=null
                    override fun requestAction(action: BlockAction)=ActionStatus.FAILED
                    override fun actionStatus()=ActionStatus.IDLE
                    override fun tickAction() {}
                    override fun cancelAction() {}
                    override fun reset() {}
                }
                CombatNavigator(context)
            }
            val routes=IntArray(count) { -1 }
            val moves=IntArray(count) { -1 }
            for(tick in 0..39) {
                navigators.forEachIndexed { i,navigator -> if(routes[i]<0) {
                    navigator.update(tick,NavVec(8.5,1.0,0.5),0.3,false,false)
                    moves[i]=navigator.firstMovementDelayTicks
                    if(navigator.firstRouteDelayTicks>=0) {
                        routes[i]=navigator.firstRouteDelayTicks
                        navigator.pause()
                    }
                } }
                if(routes.all { it>=0 })break
                now+=50_000_000
            }
            assertTrue(moves.all { it in 0..3 })
            assertTrue(routes.all { it>=0 },"bots=$count routes="+routes.toList())
            println("Navigation startup fixture: bots=$count first_move_max_ticks="+moves.max()+" first_route_max_ticks="+routes.max())
        }
    }

    @Test fun `one ten and fifty simultaneous searches remain bounded and finish`() {
        for(count in listOf(1,10,50)) {
            val searches=(1..count).map {
                val world=FixtureWorld()
                for(z in -4..4)for(y in 1..3)world.solid(7,y,z)
                IncrementalPathfinder(world,NavigationState(NavVec(0.5,1.0,0.5),0f,true,false,300),NavigationGoal(NavVec(15.5,1.0,0.5),0.3))
            }
            val done=BooleanArray(count); val expanded=IntArray(count); val times=ArrayList<Long>()
            var rounds=0
            while(done.any { !it } && rounds<500) {
                rounds++
                for(i in searches.indices) if(!done[i]) {
                    val start=System.nanoTime(); val result=searches[i].compute(); times.add(System.nanoTime()-start)
                    assertTrue(result.expanded-expanded[i]<=64); expanded[i]=result.expanded
                    if(result.status!=SearchStatus.SEARCHING) { assertEquals(SearchStatus.SUCCESS,result.status); done[i]=true }
                }
            }
            assertTrue(done.all { it }, "count=$count rounds=$rounds")
            times.sort()
            println("Navigation search fixture: bots=$count slices=${times.size} p50_ms=${times[times.size/2]/1e6} p95_ms=${times[(times.size*0.95).toInt().coerceAtMost(times.lastIndex)]/1e6} max_ms=${times.last()/1e6}")
        }
    }
}
