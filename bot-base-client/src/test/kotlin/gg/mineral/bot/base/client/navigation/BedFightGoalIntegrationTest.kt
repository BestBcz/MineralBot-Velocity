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
        override val world: NavigationWorld get()=this
        override val revision=0L
        override fun block(pos: BlockPos): NavBlock {
            val box=when {
                pos.y==0 -> NavBox(pos.x.toDouble(),0.0,pos.z.toDouble(),pos.x+1.0,1.0,pos.z+1.0)
                pos in bedState.beds.single().blocks -> NavBox(pos.x.toDouble(),1.0,pos.z.toDouble(),pos.x+1.0,1.5625,pos.z+1.0)
                else -> null
            }
            return if(box==null) NavBlock(0,replaceable=true) else
                NavBlock(if(pos.y==0)1 else 26,boxes=listOf(box),breakTicks=4.0,toolSlot=1)
        }
        override fun canBreak(pos: BlockPos,block: NavBlock)=pos in bedState.beds.single().blocks
        override fun canPlace(pos: BlockPos)=false
        override fun state()=NavigationState(NavVec(2.5,1.0,0.5),0f,true,false,300)
        override fun material(): BuildingMaterial?=null
        override fun requestAction(action: BlockAction): ActionStatus { requests.add(action); status=ActionStatus.WAITING; return status }
        override fun actionStatus()=status
        override fun tickAction() {}
        override fun move(keys: Set<Key.Type>) { this.keys=keys }
        override fun cancelAction() { status=ActionStatus.IDLE; keys=emptySet(); cancellations++ }
        override fun reset()=cancelAction()
        private val inventory=proxy<Inventory> { name,_ -> if(name=="getItemStackAt") weaponReads++; null }
        private val clientWorld=proxy<ClientWorld> { name,_ -> if(name=="getEntities") entities else null }
        val self=player<FakePlayer>(UUID(0,0),2.5)
        private val mouse=proxy<Mouse> { _,_ -> null }
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
            val bounds=proxy<BoundingBox> { name,_ -> when(name) {
                "getMinX" -> x-0.3; "getMaxX" -> x+0.3; "getMinY" -> 1.0; "getMaxY" -> 2.8
                "getMinZ" -> 0.2; "getMaxZ" -> 0.8; else -> null
            } }
            return proxy { name,_ -> when(name) {
                "getUuid" -> uuid; "getUsername" -> "Player$uuid"
                "getX","getLastX" -> x; "getY","getLastY" -> 1.0; "getZ","getLastZ" -> 0.5
                "getBoundingBox" -> bounds; "getEyeHeight" -> 1.62f
                "getInventory" -> inventory; "getWorld" -> clientWorld
                "getActivePotionEffectIds" -> intArrayOf(); "getHealth","getHunger" -> 20f
                "isOnGround" -> true; else -> null
            } }
        }
    }
    private fun objective(goal: BedFightGoal): Boolean = BedFightGoal::class.java.getDeclaredMethod("updateObjective").let {
        it.isAccessible=true; it.invoke(goal) as Boolean
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
