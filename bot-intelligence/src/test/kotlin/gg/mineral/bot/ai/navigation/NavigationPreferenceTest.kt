package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationPreferenceTest {
    private fun solve(world: FixtureWorld,goal: NavVec,material: BuildingMaterial?=null,overhead: Double=4.0,factor: Double=0.7): RouteResult {
        val search=IncrementalPathfinder(world,NavigationState(NavVec(0.5,1.0,0.5),-90f,true,false,300),
            NavigationGoal(goal,0.3),material,nanoTime={0},actionOverheadTicks=overhead,terrainCostFactor=factor)
        var result=search.compute()
        repeat(140) { if(result.status==SearchStatus.SEARCHING) result=search.compute() }
        assertEquals(SearchStatus.SUCCESS,result.status)
        return result
    }
    @Test fun `discounted dig shortcut wins over a previously preferred walk detour`() {
        val w=FixtureWorld(); w.building=true
        for(z in -2..2) for(y in 1..3) { w.solid(3,y,z); w.breakable.add(BlockPos(3,y,z)) }
        val walking=solve(w,NavVec(7.5,1.0,0.5),factor=1.0)
        val digging=solve(w,NavVec(7.5,1.0,0.5))
        assertTrue(walking.path.all { it.actions.isEmpty() })
        assertTrue(digging.path.any { it.actions.any { action -> action.kind==BlockActionKind.BREAK } })
        assertTrue(digging.path.any { it.selectionCost<it.estimatedTicks })
    }
    @Test fun `building preference selects a bridge at a detour crossover without lying about elapsed ticks`() {
        val w=FixtureWorld(); w.building=true
        for(x in 3..5) for(z in -2..2) w.gaps.add(x to z)
        for(x in -2..10) for(z in -5..5) w.solid(x,3,z)
        val material=BuildingMaterial(35,0,2,8)
        var crossover=false
        for(overhead in 4..16 step 2) {
            val normal=solve(w,NavVec(8.5,1.0,0.5),material,overhead.toDouble(),1.0)
            val eager=solve(w,NavVec(8.5,1.0,0.5),material,overhead.toDouble())
            if(normal.path.all { it.actions.isEmpty() } && eager.path.any { it.actions.isNotEmpty() }) {
                crossover=true
                assertTrue(eager.path.filter { it.actions.isNotEmpty() }.all { it.estimatedTicks>=overhead+4.0 })
                break
            }
        }
        assertTrue(crossover,"no bridge/walk crossover")
    }
}
