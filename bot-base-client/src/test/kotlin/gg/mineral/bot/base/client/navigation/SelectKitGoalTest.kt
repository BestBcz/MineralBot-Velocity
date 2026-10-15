package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.ai.goal.practice.SelectKitGoal
import gg.mineral.bot.api.controls.*
import gg.mineral.bot.api.entity.living.player.FakePlayer
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.*
import gg.mineral.bot.api.inv.item.*
import gg.mineral.bot.api.screen.Screen
import gg.mineral.bot.api.screen.type.InventoryScreen
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class SelectKitGoalTest {
    private class Fixture {
        var tick=0
        var latency=0
        var held=0
        var screen: Screen?=null
        var swapStatus=InventoryTransactionStatus.PENDING
        var swapRequested=false
        var selected: Key.Type?=null
        var mouseX=0
        var mouseY=0
        val clickTicks=mutableListOf<Int>()
        val stacks=mutableMapOf<Int,ItemStack>()
        private val book=proxy<Item> { name,_ -> if(name=="getId") Item.ENCHANTED_BOOK else null }
        private val stack=proxy<ItemStack> { name,_ -> when(name) { "getItem" -> book; "getCount" -> 1; else -> null } }
        fun book(slot: Int) { stacks[slot]=stack }
        private val inventory=proxy<Inventory> { name,args -> when(name) {
            "getItemStackAt" -> stacks[args!![0] as Int]
            "contains" -> stacks.isNotEmpty()
            "getHeldSlot" -> held
            "getHeldItemStack" -> stacks[held]
            else -> null
        } }
        private val slot=proxy<Slot> { _,_ -> null }
        private val container=proxy<InventoryContainer> { name,_ -> if(name=="getSlot") slot else null }
        private val player=proxy<FakePlayer> { name,_ -> when(name) {
            "getInventory" -> inventory; "getInventoryContainer" -> container; else -> null
        } }
        private val inventoryScreen=proxy<InventoryScreen> { name,_ -> when(name) {
            "getWidth" -> 854; "getHeight" -> 480; else -> null
        } }
        private val mouse=proxy<Mouse> { name,args -> when(name) {
            "getX" -> mouseX
            "getY" -> mouseY
            "setCursorPosition" -> { mouseX=args!![0] as Int; mouseY=args[1] as Int; null }
            "pressButton" -> {
                if((args?.last() as? Array<*>)?.contains(MouseButton.Type.RIGHT_CLICK)==true) clickTicks.add(tick)
                null
            }
            else -> null
        }
        }
        private val keyboard=proxy<Keyboard> { name,args ->
            if(name=="pressKey") for(key in args!!.last() as Array<*>) when(key) {
                Key.Type.KEY_E -> screen=inventoryScreen
                Key.Type.KEY_ESCAPE -> screen=null
                else -> if(key is Key.Type && key.name.matches(Regex("KEY_[1-9]"))) selected=key
            }
            null
        }
        val client=proxy<ClientInstance> { name,args -> when(name) {
            "getFakePlayer" -> player; "getCurrentTick" -> tick; "getLatency" -> latency
            "getMouse" -> mouse; "getKeyboard" -> keyboard; "getCurrentScreen" -> screen
            "getDisplayWidth" -> 854; "getDisplayHeight" -> 480
            "getSupportsTrackedInventoryTransactions" -> true
            "getHasPendingInventoryTransaction" -> swapRequested && swapStatus==InventoryTransactionStatus.PENDING
            "requestHotbarSwap" -> { swapRequested=true; 1L }
            "inventoryTransactionStatus" -> swapStatus
            "forgetInventoryTransaction" -> { swapRequested=false; null }
            else -> null
        } }
        val goal=SelectKitGoal(client)
        fun advance() { goal.callTick(); tick++ }
    }

    @Test fun `missing response retries right click with latency spacing and book removal confirms completion`() {
        val f=Fixture().apply { book(0); latency=400 }
        assertTrue(f.goal.checkExecute())
        assertTrue(f.goal.blocksContinuousInventory()); assertTrue(f.goal.blocksContinuousAim())
        assertTrue(f.goal.blocksContinuousAttack()); assertTrue(f.goal.blocksContinuousMovement())
        repeat(45) { f.advance() }
        assertEquals(listOf(0,20,40),f.clickTicks)
        f.stacks.clear(); f.advance()
        assertFalse(f.goal.executing); assertFalse(f.goal.checkExecute())
        f.book(0)
        assertTrue(f.goal.checkExecute(),"respawn book starts a new selection")
        f.advance(); assertEquals(4,f.clickTicks.size)
    }

    @Test fun `timeout with a remaining book cools down and remains retryable`() {
        val f=Fixture().apply { book(0) }
        assertTrue(f.goal.checkExecute())
        repeat(101) { f.advance() }
        assertFalse(f.goal.executing)
        assertFalse(f.goal.checkExecute())
        f.tick+=9
        assertTrue(f.goal.checkExecute())
        val before=f.clickTicks.size; f.advance()
        assertEquals(before+1,f.clickTicks.size)
    }

    @Test fun `delayed hotbar selection waits rather than permanently finishing the goal`() {
        val f=Fixture().apply { book(4) }
        f.goal.checkExecute(); repeat(5) { f.advance() }
        assertEquals(Key.Type.KEY_5,f.selected)
        assertTrue(f.goal.executing); assertTrue(f.clickTicks.isEmpty())
        f.held=4; f.advance()
        assertEquals(1,f.clickTicks.size)
    }

    @Test fun `backpack book waits for the tracked swap before selecting and clicking`() {
        val f=Fixture().apply { book(20) }
        f.goal.checkExecute(); f.advance(); f.advance(); f.advance()
        assertTrue(f.swapRequested); assertTrue(f.clickTicks.isEmpty())
        repeat(4) { f.advance() }
        assertTrue(f.goal.executing); assertTrue(f.clickTicks.isEmpty())
        f.stacks.clear(); f.book(8); f.swapStatus=InventoryTransactionStatus.ACCEPTED
        f.advance(); f.advance()
        assertEquals(Key.Type.KEY_9,f.selected)
        f.held=8; f.advance()
        assertEquals(1,f.clickTicks.size)
    }

    companion object {
        private inline fun <reified T> proxy(crossinline values: (String,Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { p,m,args ->
                val value=values(m.name,args)
                if(value!=null) value
                else if(m.isDefault) InvocationHandler.invokeDefault(p,m,*(args ?: emptyArray()))
                else when(m.returnType) {
                    Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L; Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0; else -> null
                }
            } as T
    }
}
