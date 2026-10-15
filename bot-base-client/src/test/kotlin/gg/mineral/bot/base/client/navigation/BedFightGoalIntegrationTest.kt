package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.ai.goal.MeleeCombatGoal
import gg.mineral.bot.ai.goal.practice.BedFightGoal
import gg.mineral.bot.ai.goal.practice.PracticeAI
import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.api.controls.*
import gg.mineral.bot.api.entity.ClientEntity
import gg.mineral.bot.api.entity.living.player.*
import gg.mineral.bot.api.event.entity.EntityHurtEvent
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.Inventory
import gg.mineral.bot.api.math.BoundingBox
import gg.mineral.bot.api.navigation.*
import gg.mineral.bot.api.world.ClientWorld
import gg.mineral.bot.api.world.block.Block
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID

/** Runs the real goal hooks and perception against a server-confirmation fixture. */
class BedFightGoalIntegrationTest {
    private class Fixture : NavigationContext, NavigationWorld {
        var tick=0
        var bedState=BedFightState(UUID(1,2),UUID(3,4),1,true,false,
            listOf(EnemyBed(1,true,setOf(BlockPos(5,1,0),BlockPos(5,1,1)))),emptyList())
        val entities=mutableListOf<ClientEntity>()
        var status=ActionStatus.IDLE
        val requests=mutableListOf<BlockAction>()
        var keys=emptySet<Key.Type>()
        var cancellations=0
        var weaponReads=0
        var stateVisible=true
        var utility=false
        var rightClick=false
        var aimHeld=false
        var aimChanges=0
        var enemyFeet=1.0
        var enemyGrounded=true
        var island: Int?=null
        var feet=NavVec(2.5,1.0,0.5)
        var building: BuildingMaterial?=null
        val placed=mutableMapOf<BlockPos,NavBlock>()
        override val world: NavigationWorld get()=this
        override val revision=0L
        override val ownsAim get()=aimHeld
        override fun block(pos: BlockPos): NavBlock {
            placed[pos]?.let { return it }
            val box=when {
                pos.y==0 && (island==null || pos.x<=2 || pos.x>=island!!) -> NavBox(pos.x.toDouble(),0.0,pos.z.toDouble(),pos.x+1.0,1.0,pos.z+1.0)
                pos in bedState.beds.single().blocks -> NavBox(pos.x.toDouble(),1.0,pos.z.toDouble(),pos.x+1.0,1.5625,pos.z+1.0)
                else -> null
            }
            return if(box==null) NavBlock(0,replaceable=true) else
                NavBlock(if(pos.y==0)1 else 26,boxes=listOf(box),breakTicks=4.0,toolSlot=1)
        }
        override fun canBreak(pos: BlockPos,block: NavBlock)=pos in bedState.beds.single().blocks
        override fun canPlace(pos: BlockPos)=building!=null
        override fun state()=NavigationState(feet,-90f,true,false,300)
        override fun material()=building
        override fun requestAction(action: BlockAction): ActionStatus { requests.add(action); status=ActionStatus.WAITING; return status }
        override fun actionStatus()=status
        override fun tickAction() {}
        override fun move(keys: Set<Key.Type>) { this.keys=keys }
        override fun cancelAction() { status=ActionStatus.IDLE; keys=emptySet(); cancellations++ }
        override fun reset()=cancelAction()
        private val inventory=proxy<Inventory> { name,_ -> if(name=="getItemStackAt") weaponReads++; null }
        private val clientWorld=proxy<ClientWorld> { name,args -> when(name) {
            "getEntities" -> entities
            "getBlockAt" -> {
                val b=block(BlockPos.at((args!![0] as Number).toDouble(),(args[1] as Number).toDouble(),(args[2] as Number).toDouble()))
                proxy<Block> { method,_ -> when(method) {
                    "getId" -> b.id
                    "getCollisionBoundingBox" -> b.boxes.firstOrNull()?.let { box -> proxy<BoundingBox> { property,_ -> when(property) {
                        "getMinX" -> box.minX; "getMinY" -> box.minY; "getMinZ" -> box.minZ
                        "getMaxX" -> box.maxX; "getMaxY" -> box.maxY; "getMaxZ" -> box.maxZ; else -> null
                    } } }
                    else -> null
                } }
            }
            else -> null
        } }
        val self=player<FakePlayer>(UUID(0,0),2.5)
        private val mouse=proxy<Mouse> { name,_ ->
            when(name) {
                "clearPendingClicks","unpressButton" -> rightClick=false
                "changeYaw","changePitch" -> aimChanges++
            }
            null
        }
        private val keyboard=proxy<Keyboard> { _,_ -> null }
        private val config=BotConfiguration(friendlyUUIDs=mutableSetOf())
        val client=proxy<ClientInstance> { name,_ -> when(name) {
            "getFakePlayer" -> self
            "getNavigationContext" -> this
            "getBedFightState" -> if(stateVisible) bedState else null
            "getHasActiveSporadicGoal" -> utility
            "getConfiguration" -> config
            "getCurrentTick" -> tick
            "getMouse" -> mouse
            "getKeyboard" -> keyboard
            "timeMillis" -> tick*50L
            else -> null
        } }
        inline fun <reified T : ClientPlayer> player(uuid: UUID,x: Double): T {
            val position={ if(uuid==UUID(0,0))feet else NavVec(x,enemyFeet,0.5) }
            val bounds=proxy<BoundingBox> { name,_ -> when(name) {
                "getMinX" -> position().x-0.3; "getMaxX" -> position().x+0.3; "getMinY" -> position().y; "getMaxY" -> position().y+1.8
                "getMinZ" -> 0.2; "getMaxZ" -> 0.8; else -> null
            } }
            return proxy { name,_ -> when(name) {
                "getUuid" -> uuid; "getUsername" -> "Player$uuid"
                "getX","getLastX" -> position().x; "getY","getLastY" -> position().y; "getZ","getLastZ" -> position().z
                "getBoundingBox" -> bounds; "getEyeHeight" -> 1.62f
                "getInventory" -> inventory; "getWorld" -> clientWorld
                "getActivePotionEffectIds" -> intArrayOf(); "getHealth","getHunger" -> 20f
                "getRandom" -> java.util.Random(1)
                "isOnGround" -> uuid==UUID(0,0) || enemyGrounded; else -> null
            } }
        }
    }
    private fun objective(goal: BedFightGoal): Boolean = BedFightGoal::class.java.getDeclaredMethod("updateObjective").let {
        it.isAccessible=true; it.invoke(goal) as Boolean
    }
    @Test fun `foreground kit click survives waiting active and respawn BedFight states`() {
        for(phase in 0..2) {
            val f=Fixture().apply {
                utility=true; rightClick=true
                if(phase==0) bedState=bedState.copy(active=false)
                if(phase==2) bedState=bedState.copy(selfRespawning=true)
            }
            val goal=BedFightGoal(f.client)
            repeat(5) { goal.onGameLoop(); goal.onTick(Goal.Tick(it,goal)); f.tick++ }
            assertTrue(f.rightClick,"phase=$phase")
            assertTrue(f.requests.isEmpty()); assertTrue(f.keys.isEmpty())
            f.utility=false; f.tick++; goal.onGameLoop()
            if(phase!=1) assertFalse(f.rightClick)
        }
    }
    @Test fun `bridge travel aim is not overwritten by the bed walking view`() {
        val f=Fixture().apply {
            aimHeld=true
            bedState=bedState.copy(beds=listOf(EnemyBed(1,true,setOf(BlockPos(48,1,0)))))
        }
        val goal=BedFightGoal(f.client)
        goal.onGameLoop(); goal.onTick(Goal.Tick(0,goal))
        assertEquals(0,f.aimChanges)
    }
    @Test fun `real bed goal climbs for opponents sixteen thirty two and forty eight blocks away`() {
        for(distance in listOf(16,32,48)) {
            val f=Fixture().apply {
                island=distance+2; building=BuildingMaterial(35,0,2,64); enemyFeet=6.0
                bedState=bedState.copy(beds=listOf(EnemyBed(1,true,setOf(BlockPos(distance+2,1,0)))))
            }
            val id=UUID(0,1)
            f.entities.add(f.player<ClientPlayer>(id,distance+2.5))
            f.bedState=f.bedState.copy(opponents=listOf(BedFightOpponent(id,BedFightLife.ALIVE)))
            val goal=BedFightGoal(f.client)
            var climbed=false
            while(f.tick<20 && !climbed) {
                goal.onGameLoop()
                if(f.status==ActionStatus.WAITING) {
                    val a=f.requests.last()
                    assertEquals(BlockActionKind.PLACE,a.kind)
                    f.placed[a.pos]=NavBlock(a.blockId,boxes=listOf(NavBox(a.pos.x.toDouble(),a.pos.y.toDouble(),
                        a.pos.z.toDouble(),a.pos.x+1.0,a.pos.y+1.0,a.pos.z+1.0)))
                    f.feet=NavVec(a.pos.x+0.5,a.pos.y+1.0,a.pos.z+0.5)
                    f.status=ActionStatus.CONFIRMED
                    climbed=a.pos.y>0
                }
                f.tick++
            }
            assertTrue(climbed,"distance=$distance ${goal.debugSummary()}")
            assertTrue(goal.debugSummary().contains("BED"))
            assertTrue(f.feet.x<10.0,"height response must precede close combat")
        }
    }
    @Test fun `real goal cancels bed dig on proximity and resumes on confirmed temporary death`() {
        val f=Fixture(); val goal=BedFightGoal(f.client)
        goal.onGameLoop(); goal.onTick(Goal.Tick(0,goal))
        assertEquals(1,f.requests.size); assertEquals(0,f.weaponReads)
        val uuid=UUID(0,1); val enemy=f.player<ClientPlayer>(uuid,8.0)
        f.entities.add(enemy); f.bedState=f.bedState.copy(opponents=listOf(BedFightOpponent(uuid,BedFightLife.ALIVE)))
        f.tick++
        assertFalse(objective(goal)); assertEquals(ActionStatus.IDLE,f.status)
        assertTrue(goal.debugSummary().contains("COMBAT"))
        goal.onEntityHurt(EntityHurtEvent(f.self))
        f.bedState=f.bedState.copy(opponents=listOf(BedFightOpponent(uuid,BedFightLife.RESPAWNING,60)))
        f.tick++; goal.onGameLoop()
        assertEquals(2,f.requests.size); assertEquals(ActionStatus.WAITING,f.status)
        assertTrue(goal.debugSummary().contains("BED"))
    }
    @Test fun `remote player on a high platform is tracked even without the legacy onGround flag`() {
        val f=Fixture().apply {
            island=34; building=BuildingMaterial(35,0,2,64); enemyFeet=6.0; enemyGrounded=false
            bedState=bedState.copy(beds=listOf(EnemyBed(1,true,setOf(BlockPos(34,1,0)))))
            placed[BlockPos(34,5,0)]=NavBlock(35,boxes=listOf(NavBox(34.0,5.0,0.0,35.0,6.0,1.0)))
        }
        val id=UUID(0,1)
        f.entities.add(f.player<ClientPlayer>(id,34.5))
        f.bedState=f.bedState.copy(opponents=listOf(BedFightOpponent(id,BedFightLife.ALIVE)))
        val goal=BedFightGoal(f.client)
        while(f.requests.isEmpty() && f.tick<10) { goal.onGameLoop(); f.tick++ }
        val navigator=MeleeCombatGoal::class.java.getDeclaredField("navigator").also { it.isAccessible=true }.get(goal)
        val planned=navigator.javaClass.getDeclaredField("plannedGoal").also { it.isAccessible=true }.get(navigator)
            as gg.mineral.bot.ai.navigation.NavigationGoal
        assertEquals(6.0,planned.preferredBridgeHeight)
        assertTrue(goal.debugSummary().contains("BED"))
        assertTrue(f.requests.isNotEmpty())
    }
    @Test fun `self respawn stops a pending operation and missing state returns control to melee`() {
        val f=Fixture(); val goal=BedFightGoal(f.client)
        goal.onGameLoop(); f.bedState=f.bedState.copy(selfRespawning=true); f.tick++
        goal.onGameLoop(); assertEquals(ActionStatus.IDLE,f.status); assertTrue(f.keys.isEmpty())
        assertTrue(goal.debugSummary().contains("WAIT"))
        assertEquals(1,f.requests.size)
        f.stateVisible=false; f.tick++
        assertFalse(objective(goal)); assertTrue(goal.debugSummary().contains("FALLBACK"))
        assertEquals(ActionStatus.IDLE,f.status)
    }
    @Test fun `utility goal interrupts bed controls and the bed operation resumes afterward`() {
        val f=Fixture(); val goal=BedFightGoal(f.client)
        goal.onGameLoop(); assertEquals(ActionStatus.WAITING,f.status)
        f.utility=true; f.tick++; goal.onGameLoop()
        assertEquals(ActionStatus.IDLE,f.status); assertEquals(1,f.requests.size)
        f.utility=false; f.tick++; goal.onGameLoop()
        assertEquals(ActionStatus.WAITING,f.status); assertEquals(2,f.requests.size)
    }
    @Test fun `BedFight kit has one shared combat controller`() {
        val goals=PracticeAI.getGoalsForKit(Fixture().client,"BED_FIGHT")
        assertEquals(1,goals.count { it is MeleeCombatGoal })
        assertTrue(goals.last() is BedFightGoal)
    }
    @Test fun `real bed goal starts distant island construction within ten ticks and utility releases it`() {
        for(distance in listOf(16,32,48)) {
            val f=Fixture().apply {
                island=distance+2; building=BuildingMaterial(35,0,2,64)
                bedState=bedState.copy(beds=listOf(EnemyBed(1,true,setOf(BlockPos(distance+2,1,0),BlockPos(distance+2,1,1)))))
            }
            val goal=BedFightGoal(f.client)
            while(f.requests.isEmpty() && f.tick<10) { goal.onGameLoop(); f.tick++ }
            assertEquals(PlacementStyle.BRIDGE,f.requests.single().placement)
            assertEquals(BlockPos(3,0,0),f.requests.single().pos)
            assertEquals(0,f.weaponReads)
            f.utility=true; goal.onGameLoop()
            assertEquals(ActionStatus.IDLE,f.status); assertTrue(f.keys.isEmpty())
            f.utility=false
            val resume=f.tick
            while(f.requests.size<2 && f.tick-resume<10) { f.tick++; goal.onGameLoop() }
            assertEquals(2,f.requests.size)
        }
    }
    @Test fun `combat approach and hunt retain BedFight construction and respawn resumes bed routing`() {
        for(hunt in listOf(false,true)) {
            val f=Fixture().apply { island=6; building=BuildingMaterial(35,0,2,64) }
            val uuid=UUID(0,1)
            f.entities.add(f.player<ClientPlayer>(uuid,6.5))
            f.bedState=f.bedState.copy(beds=listOf(EnemyBed(1,!hunt,setOf(BlockPos(18,1,0),BlockPos(18,1,1)))),
                opponents=listOf(BedFightOpponent(uuid,BedFightLife.ALIVE)))
            val goal=BedFightGoal(f.client)
            while(f.requests.isEmpty() && f.tick<12) { goal.onGameLoop(); f.tick++ }
            assertTrue(goal.debugSummary().contains(if(hunt)"HUNT" else "COMBAT"))
            assertEquals(PlacementStyle.BRIDGE,f.requests.single().placement)
            assertEquals(BlockPos(3,0,0),f.requests.single().pos)
            if(!hunt) {
                goal.onEntityHurt(EntityHurtEvent(f.self))
                f.bedState=f.bedState.copy(opponents=listOf(BedFightOpponent(uuid,BedFightLife.RESPAWNING,60)))
                val resume=f.tick
                while(f.requests.size<2 && f.tick-resume<10) { f.tick++; goal.onGameLoop() }
                assertTrue(goal.debugSummary().contains("BED"))
                assertEquals(2,f.requests.size)
                assertEquals(PlacementStyle.BRIDGE,f.requests.last().placement)
            }
        }
    }
    companion object {
        private inline fun <reified T> proxy(crossinline values: (String,Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { p,m,args ->
                val value=values(m.name,args)
                if(value!=null) value
                else if(m.isDefault) InvocationHandler.invokeDefault(p,m,*(args ?: emptyArray()))
                else when(m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            } as T
    }
}
