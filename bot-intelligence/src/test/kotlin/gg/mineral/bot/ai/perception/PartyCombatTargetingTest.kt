package gg.mineral.bot.ai.perception

import gg.mineral.bot.ai.goal.MeleeCombatGoal
import gg.mineral.bot.ai.goal.ThrowDebuffPotGoal
import gg.mineral.bot.ai.goal.ThrowPearlGoal
import gg.mineral.bot.ai.goal.practice.FishingRodGoal
import gg.mineral.bot.ai.goal.practice.SumoCombatGoal
import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.api.controls.Keyboard
import gg.mineral.bot.api.controls.Mouse
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.ClientEntity
import gg.mineral.bot.api.entity.living.player.ClientPlayer
import gg.mineral.bot.api.entity.living.player.FakePlayer
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.instance.CombatTargetState
import gg.mineral.bot.api.inv.Inventory
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.inv.item.ItemStack
import gg.mineral.bot.api.math.BoundingBox
import gg.mineral.bot.api.world.ClientWorld
import gg.mineral.bot.api.world.block.Block
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID

class PartyCombatTargetingTest {
    private class Fixture {
        var tick = 0
        var firstX = 4.0
        var secondX = 8.0
        var rightClicks = 0
        val config = BotConfiguration(friendlyUUIDs = mutableSetOf())
        val selection = CombatTargetState()
        val entities = mutableListOf<ClientEntity>()
        val slots = mutableMapOf<Int, ItemStack>()
        private val inventory = proxy<Inventory> { name, args ->
            if (name == "getItemStackAt") slots[args!![0] as Int] else null
        }
        private val air = proxy<Block> { name, _ -> if (name == "getId") Block.AIR else null }
        private val world = proxy<ClientWorld> { name, _ -> when (name) {
            "getEntities" -> entities
            "getBlockAt" -> air
            else -> null
        } }
        val self = player<FakePlayer>(0) { 0.0 }
        val first = player<ClientPlayer>(1) { firstX }
        val second = player<ClientPlayer>(2) { secondX }
        private val mouse = proxy<Mouse> { name, args ->
            if (name == "pressButton" && args?.any { value ->
                    value is Array<*> && MouseButton.Type.RIGHT_CLICK in value
                } == true) rightClicks++
            null
        }
        private val keyboard = proxy<Keyboard> { _, _ -> null }
        val client = proxy<ClientInstance> { name, _ -> when (name) {
            "getFakePlayer" -> self
            "getConfiguration" -> config
            "getCurrentTick" -> tick
            "getMatchTargetUuid" -> first.uuid
            "getCombatTargetState" -> selection
            "getMouse" -> mouse
            "getKeyboard" -> keyboard
            else -> null
        } }

        init { entities.addAll(listOf(first, second)) }

        private inline fun <reified T : ClientPlayer> player(id: Long, crossinline x: () -> Double): T {
            val bounds = proxy<BoundingBox> { name, _ -> when (name) {
                "getMinX" -> x() - 0.3; "getMaxX" -> x() + 0.3
                "getMinY" -> 64.0; "getMaxY" -> 65.8
                "getMinZ" -> -0.3; "getMaxZ" -> 0.3
                else -> null
            } }
            return proxy { name, _ -> when (name) {
                "getUuid" -> UUID(0, id)
                "getEntityId" -> id.toInt()
                "getUsername" -> "Player$id"
                "getX", "getLastX" -> x()
                "getY", "getLastY" -> 64.0
                "getZ", "getLastZ" -> 0.0
                "getBoundingBox" -> bounds
                "getEyeHeight" -> 1.62f
                "getInventory" -> inventory
                "getWorld" -> world
                "getActivePotionEffectIds" -> intArrayOf()
                "getHealth", "getHunger" -> 20f
                "getRandom" -> java.util.Random(1)
                "isOnGround" -> true
                else -> null
            } }
        }
    }

    @Test fun `real melee and sumo goals both change target during a two player fight`() {
        for (sumo in listOf(false, true)) {
            val f = Fixture()
            val goal: Goal = if (sumo) SumoCombatGoal(f.client) else MeleeCombatGoal(f.client)
            goal.onTick(Goal.Tick(0, goal))
            assertEquals(f.first.uuid, f.selection.targetUuid)
            f.firstX = 12.0
            f.secondX = 2.0
            f.tick = 10
            goal.onTick(Goal.Tick(1, goal))
            assertEquals(f.second.uuid, f.selection.targetUuid, "sumo=$sumo")
            val target = goal.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(goal) as ClientPlayer
            assertEquals(f.second.uuid, target.uuid)
        }
    }

    @Test fun `friendly publication invalidates perception within the same tick and across goals`() {
        val f = Fixture()
        val meleeView = CombatPerception(f.client)
        val utilityView = CombatPerception(f.client)
        assertEquals(f.first.uuid, meleeView.bestTarget()?.uuid)
        assertEquals(f.first.uuid, utilityView.bestTarget()?.uuid)
        f.config.friendlyUUIDs = mutableSetOf(f.first.uuid)
        assertEquals(f.second.uuid, meleeView.bestTarget()?.uuid)
        assertEquals(f.second.uuid, utilityView.bestTarget()?.uuid)
        assertFalse(meleeView.snapshot().enemies.any { it.uuid == f.first.uuid })
        f.config.friendlyUUIDs = mutableSetOf(f.first.uuid, f.second.uuid)
        assertNull(utilityView.bestTarget())
        assertNull(f.selection.targetUuid)
    }

    @Test fun `rod keeps its cast target and cancels when that target becomes a teammate`() {
        val f = Fixture()
        val rod = FishingRodGoal(f.client)
        rod.callStart()
        val targetId = FishingRodGoal::class.java.getDeclaredField("targetId").apply { isAccessible = true }
        assertEquals(f.first.entityId, targetId.getInt(rod))
        f.tick = 10; f.firstX = 12.0; f.secondX = 2.0
        assertEquals(f.second.uuid, CombatPerception(f.client).bestTarget()?.uuid)
        assertEquals(f.first.entityId, targetId.getInt(rod))
        f.config.friendlyUUIDs = mutableSetOf(f.first.uuid)
        rod.onTick(Goal.Tick(0, rod))
        val returning = FishingRodGoal::class.java.getDeclaredField("returning").apply { isAccessible = true }
        assertTrue(returning.getBoolean(rod))
        assertEquals(0, f.rightClicks)
    }

    @Test fun `offensive throws keep their selected opponent and cancel invalid casts`() {
        for (debuff in listOf(false, true)) {
            val f = Fixture().apply {
                firstX = 6.5; secondX = 9.0
                slots[0] = proxy { name, _ -> when (name) {
                    "getItem" -> proxy<Item> { property, _ -> if (property == "getId") Item.ENDER_PEARL else null }
                    "getCount" -> 16
                    else -> null
                } }
            }
            val goal: Goal = if (debuff) ThrowDebuffPotGoal(f.client) else ThrowPearlGoal(f.client)
            val sporadic = goal as gg.mineral.bot.api.goal.Sporadic
            sporadic.callStart()
            val castTarget = goal.javaClass.getDeclaredField("castTargetUuid").apply { isAccessible = true }
            assertEquals(f.first.uuid, castTarget.get(goal))
            f.tick = 10; f.firstX = 12.0; f.secondX = 2.0
            assertEquals(f.second.uuid, CombatPerception(f.client).bestTarget()?.uuid)
            assertEquals(f.first.uuid, castTarget.get(goal))
            f.config.friendlyUUIDs = mutableSetOf(f.first.uuid)
            val step = Goal.Tick(0, goal)
            goal.onTick(step)
            assertTrue(step.finished, "debuff=$debuff")
            assertEquals(0, f.rightClicks)
            sporadic.callEnd()
        }
    }

    companion object {
        private inline fun <reified T> proxy(crossinline values: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, method, args ->
                val value = values(method.name, args)
                if (value != null) value
                else if (method.isDefault) InvocationHandler.invokeDefault(p, method, *(args ?: emptyArray()))
                else when (method.returnType) {
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
