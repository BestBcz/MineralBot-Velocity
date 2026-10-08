package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationContinuityTest {
    private class Context : NavigationContext {
        override val world = FixtureWorld()
        override var searchBudget = NavigationSearchBudget.UNLIMITED
        var player = NavigationState(NavVec(0.5,1.0,0.5),-90f,true,false,300)
        var material: BuildingMaterial? = null
        var keys = emptySet<Key.Type>()
        var status = ActionStatus.IDLE
        val requests = mutableListOf<BlockAction>()
        var cancellations = 0
        override fun state() = player
        override fun material() = material
        override fun move(keys: Set<Key.Type>) { this.keys = keys }
        override fun requestAction(action: BlockAction): ActionStatus {
            requests.add(action); status = ActionStatus.WAITING; return status
        }
        override fun actionStatus() = status
        override fun tickAction() {}
        override fun cancelAction() { cancellations++; keys = emptySet(); status = ActionStatus.IDLE }
        override fun reset() = cancelAction()
        fun confirm() {
            val action = requests.last()
            if(action.kind==BlockActionKind.PLACE) {
                world.solid(action.pos.x,action.pos.y,action.pos.z,action.blockId)
                material = material?.copy(count=material!!.count-1)
            } else world.blocks[action.pos] = NavBlock(0,replaceable=true)
            world.revision++
            status = ActionStatus.CONFIRMED
        }
    }

    @Test fun `five second damage protection tracks fresh damage but not healing`() {
        var now = 0L
        val protection = CombatNavigationProtection { now }
        protection.observeHealth(20f)
        assertFalse(protection.active)
        protection.observeHealth(19f)
        now = 4999; assertTrue(protection.active)
        protection.observeHealth(20f)
        now = 5000; assertFalse(protection.active)
        protection.hurt(); now = 9999; assertTrue(protection.active)
        now = 10000; assertFalse(protection.active)
    }

    @Test fun `open pursuit keeps combat controls even outside melee range`() {
        val ctx = Context()
        ctx.keys = setOf(Key.Type.KEY_W,Key.Type.KEY_LCONTROL)
        val navigator = CombatNavigator(ctx)
        assertFalse(navigator.update(1,NavVec(9.5,1.0,0.5),3.35,true,true,
            NavigationCombatState(protected=true,preserveOpenMovement=true)))
        assertEquals(setOf(Key.Type.KEY_W,Key.Type.KEY_LCONTROL),ctx.keys)
        assertEquals(0,ctx.cancellations)
        assertEquals(0,navigator.searchesStarted)
    }

    @Test fun `recent damage still permits blocked escape without waiting five seconds`() {
        val ctx = Context()
        for(z in -2..2) for(y in 1..3) ctx.world.solid(2,y,z)
        val navigator = CombatNavigator(ctx)
        assertTrue(navigator.update(1,NavVec(8.5,1.0,0.5),3.35,false,false,
            NavigationCombatState(protected=true,preserveOpenMovement=true)))
        assertTrue(ctx.keys.any { it in setOf(Key.Type.KEY_W,Key.Type.KEY_A,Key.Type.KEY_S,Key.Type.KEY_D) })
        assertEquals(0,navigator.firstMovementDelayTicks)
    }

    @Test fun `an airborne opponent and our own combat jump do not start unnecessary navigation`() {
        val ctx=Context(); val navigator=CombatNavigator(ctx)
        ctx.keys=setOf(Key.Type.KEY_W,Key.Type.KEY_LCONTROL)
        val combat=NavigationCombatState(protected=true,preserveOpenMovement=true)
        assertFalse(navigator.update(1,NavVec(8.5,1.7,0.5),3.35,true,true,combat))
        ctx.player=ctx.player.copy(position=NavVec(0.5,1.42,0.5),onGround=false)
        assertFalse(navigator.update(2,NavVec(8.5,1.7,0.5),3.35,true,true,combat))
        assertEquals(0,navigator.searchesStarted)
        assertTrue(Key.Type.KEY_W in ctx.keys)
    }

    @Test fun `budget waits move safely and target crossing a block does not restart the search`() {
        val ctx = Context()
        ctx.searchBudget = object : NavigationSearchBudget { override fun acquire(owner: Any)=0L }
        for(z in -2..2) for(y in 1..3) ctx.world.solid(2,y,z)
        val navigator = CombatNavigator(ctx)
        for(tick in 1..8) {
            navigator.update(tick,NavVec(7.9+tick*0.05,1.0,0.5),0.3,false,false)
            assertTrue(ctx.keys.isNotEmpty())
        }
        assertEquals(1,navigator.searchesStarted)
        navigator.pause()
        assertTrue(ctx.keys.isEmpty())
    }

    @Test fun `an approaching opponent interrupts an outstanding terrain action`() {
        val ctx = Context(); ctx.world.building = true
        for(x in -1..1) for(z in -1..1) if(x!=0 || z!=0) for(y in 1..3) ctx.world.solid(x,y,z)
        ctx.world.breakable.addAll(listOf(BlockPos(1,1,0),BlockPos(1,2,0)))
        val navigator = CombatNavigator(ctx)
        var tick = 0
        while(!navigator.ownsInteraction && tick<100) navigator.update(++tick,NavVec(7.5,1.0,0.5),0.3,false,true)
        assertTrue(navigator.ownsInteraction)
        assertFalse(navigator.update(++tick,NavVec(1.5,1.0,0.5),3.35,true,true))
        assertFalse(navigator.ownsInteraction)
        assertEquals(ActionStatus.IDLE,ctx.status)
    }

    @Test fun `small target movement preserves a pending block operation`() {
        val ctx = Context(); ctx.world.building = true
        for(x in -1..1) for(z in -1..1) if(x!=0 || z!=0) for(y in 1..3) ctx.world.solid(x,y,z)
        ctx.world.breakable.addAll(listOf(BlockPos(1,1,0),BlockPos(1,2,0)))
        val navigator = CombatNavigator(ctx)
        var tick = 0
        while(!navigator.ownsInteraction && tick<100) navigator.update(++tick,NavVec(7.9,1.0,0.5),0.3,false,true)
        assertTrue(navigator.ownsInteraction)
        val cancellations = ctx.cancellations
        repeat(10) { navigator.update(++tick,NavVec(8.1,1.0,0.5),0.3,false,true) }
        assertEquals(1,ctx.requests.size)
        assertEquals(cancellations,ctx.cancellations)
    }

    @Test fun `upstream swimming holds forward input across waypoints`() {
        val ctx = Context()
        // Banks cannot be climbed here, so this specifically checks swimming rather than a valid land detour.
        for(z in -40..40) for(y in 1..10) { ctx.world.solid(-5,y,z); ctx.world.solid(5,y,z) }
        for(x in -4..4) for(z in -4..20) for(y in 1..4)
            ctx.world.blocks[BlockPos(x,y,z)] = NavBlock(8,water=true,replaceable=true,flow=NavVec(0.0,0.0,-1.0))
        ctx.player = ctx.player.copy(position=NavVec(0.5,2.0,0.5),yaw=0f,onGround=false,inWater=true)
        val navigator = CombatNavigator(ctx)
        for(tick in 1..35) {
            ctx.player = ctx.player.copy(position=NavVec(0.5,2.0,0.5+tick*0.07),velocity=NavVec(0.0,0.0,-0.05))
            navigator.update(tick,NavVec(0.5,2.0,12.5),0.3,false,false)
            assertTrue(Key.Type.KEY_W in ctx.keys,"tick=$tick state="+navigator.diagnostic+" keys="+ctx.keys+" yaw="+navigator.steeringYaw)
            assertFalse(Key.Type.KEY_LCONTROL in ctx.keys)
        }
    }

    @Test fun `three confirmed bridge blocks continue the original route`() {
        val ctx = Context(); ctx.world.building = true
        ctx.material = BuildingMaterial(35,0,2,8)
        for(x in 1..3) for(z in -40..40) ctx.world.gaps.add(x to z)
        // A low ceiling makes this a bridge, rather than a two-block gap jump.
        for(x in -2..6) for(z in -40..40) ctx.world.solid(x,3,z)
        val navigator = CombatNavigator(ctx)
        var tick = 0
        var searchesAtFirstOperation = -1
        while(ctx.requests.size<3 && tick<120) {
            navigator.update(++tick,NavVec(5.5,1.0,0.5),0.3,false,true)
            if(ctx.status==ActionStatus.WAITING) {
                val a = ctx.requests.last()
                assertEquals(PlacementStyle.BRIDGE,a.placement)
                if(searchesAtFirstOperation<0) searchesAtFirstOperation = navigator.searchesStarted
                ctx.confirm()
                ctx.player = ctx.player.copy(position=NavVec(a.pos.x+0.5,a.pos.y+1.0,a.pos.z+0.5))
            }
        }
        assertEquals(3,ctx.requests.size,navigator.diagnostic)
        assertEquals(searchesAtFirstOperation,navigator.searchesStarted)
    }

    @Test fun `three tower levels preserve the route and high target bypasses near protection`() {
        val ctx = Context(); ctx.world.building = true
        ctx.material = BuildingMaterial(35,0,2,8)
        val navigator = CombatNavigator(ctx)
        var tick = 0
        var searchesAtFirstOperation = -1
        while(ctx.requests.size<3 && tick<150) {
            navigator.update(++tick,NavVec(0.5,5.0,0.5),3.35,true,true,
                NavigationCombatState(protected=true,preserveOpenMovement=true))
            if(ctx.status==ActionStatus.WAITING) {
                val a = ctx.requests.last()
                assertEquals(PlacementStyle.TOWER,a.placement)
                if(searchesAtFirstOperation<0) searchesAtFirstOperation = navigator.searchesStarted
                ctx.confirm()
                ctx.player = ctx.player.copy(position=NavVec(a.pos.x+0.5,a.pos.y+1.0,a.pos.z+0.5))
            }
        }
        assertEquals(3,ctx.requests.size,navigator.diagnostic)
        assertEquals(searchesAtFirstOperation,navigator.searchesStarted)
    }

    @Test fun `two authorized break actions advance without a new search`() {
        val ctx = Context(); ctx.world.building = true
        for(x in -1..1) for(z in -1..1) if(x!=0 || z!=0) for(y in 1..3) ctx.world.solid(x,y,z)
        ctx.world.breakable.addAll(listOf(BlockPos(1,1,0),BlockPos(1,2,0)))
        val navigator = CombatNavigator(ctx)
        var tick = 0
        while(ctx.requests.size<2 && tick<100) {
            navigator.update(++tick,NavVec(7.5,1.0,0.5),0.3,false,true)
            if(ctx.status==ActionStatus.WAITING) ctx.confirm()
        }
        assertEquals(2,ctx.requests.size,navigator.diagnostic)
        assertEquals(1,navigator.searchesStarted)
    }

    @Test fun `a high tower starts a useful prefix before the virtual terrain batch limit`() {
        val ctx=Context(); ctx.world.building=true
        ctx.material=BuildingMaterial(35,0,2,32)
        val navigator=CombatNavigator(ctx)
        var tick=0
        while(!navigator.ownsInteraction && tick<10)
            navigator.update(++tick,NavVec(0.5,16.0,0.5),3.35,true,true,
                NavigationCombatState(protected=true,preserveOpenMovement=true))
        assertTrue(navigator.ownsInteraction,navigator.diagnostic)
        assertEquals(PlacementStyle.TOWER,ctx.requests.first().placement)
        assertTrue(navigator.firstRouteDelayTicks<=9)
    }
}
