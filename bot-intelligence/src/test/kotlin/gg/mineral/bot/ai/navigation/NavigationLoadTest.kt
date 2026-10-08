package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Measures sliced search only, not live Minecraft/server throughput. */
class NavigationLoadTest {
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
