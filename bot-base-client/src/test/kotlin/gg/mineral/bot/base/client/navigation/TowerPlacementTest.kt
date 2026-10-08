package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.api.navigation.NavVec
import gg.mineral.bot.api.navigation.NavigationState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TowerPlacementTest {
    private fun state(y: Double,ground: Boolean)=NavigationState(NavVec(0.5,y,0.5),0f,ground,false,300)
    @Test fun `tower takes off once places in the window and waits for confirmed landing`() {
        val tower=TowerPlacement(2.0)
        assertEquals(TowerPlacement.Motion.TAKEOFF,tower.update(state(1.0,true),false,false))
        assertEquals(TowerPlacement.Motion.RISING,tower.update(state(1.42,false),false,false))
        assertEquals(TowerPlacement.Motion.PLACE,tower.update(state(2.16,false),false,false))
        repeat(20) { assertEquals(TowerPlacement.Motion.WAITING,tower.update(state(2.0,true),true,false)) }
        assertEquals(TowerPlacement.Motion.LANDING,tower.update(state(2.05,false),true,true))
        assertEquals(TowerPlacement.Motion.COMPLETE,tower.update(state(2.0,true),true,true))
    }
    @Test fun `missing the placement window fails instead of jumping again`() {
        val tower=TowerPlacement(2.0)
        tower.update(state(1.0,true),false,false)
        tower.update(state(1.42,false),false,false)
        repeat(5) { assertEquals(TowerPlacement.Motion.FAILED,tower.update(state(1.0,true),false,false)) }
    }
    @Test fun `server confirmation alone does not finish a tower before landing on its support`() {
        val tower=TowerPlacement(2.0)
        assertEquals(TowerPlacement.Motion.LANDING,tower.update(state(1.0,true),true,true))
        assertEquals(TowerPlacement.Motion.LANDING,tower.update(state(2.0,false),true,true))
    }
}
