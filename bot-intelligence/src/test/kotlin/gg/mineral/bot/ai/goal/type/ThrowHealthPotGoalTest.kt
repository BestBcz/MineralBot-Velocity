package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.ai.goal.ThrowHealthPotGoal
import gg.mineral.bot.api.configuration.BotDifficulty
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.event.entity.EntityHealthUpdateEvent
import gg.mineral.bot.api.inv.item.Item
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThrowHealthPotGoalTest {
    private fun fixture(health: Float = 4f, potions: Int = 8) = SupplyTestFixture().apply {
        selfData.health = health
        selfData.slots[0] = SupplyTestFixture.Stack(Item.POTION, potions, durability = 16421)
        selfData.slots[1] = SupplyTestFixture.Stack(Item.DIAMOND_SWORD, attackDamage = 7.0)
        config.healthPotAimWaitTicks = 0
    }

    private fun advance(f: SupplyTestFixture, goal: ThrowHealthPotGoal, ticks: Int = 1) {
        repeat(ticks) {
            f.tick++
            if (goal.executing) goal.callTick()
        }
    }

    private fun firstThrow(f: SupplyTestFixture): ThrowHealthPotGoal {
        val goal = ThrowHealthPotGoal(f.client)
        f.start(goal)
        repeat(40) {
            if (f.rightClicks == 1) return goal
            advance(f, goal)
        }
        error("Potion was not thrown: ${goal.debugSummary()}")
    }

    private fun consume(f: SupplyTestFixture) {
        val slot = f.selfData.heldSlot
        val stack = f.selfData.slots.getValue(slot)
        check(stack.item.id == Item.POTION)
        if (--stack.count == 0) f.selfData.slots.remove(slot)
        f.selfData.consuming = false
    }

    private fun health(f: SupplyTestFixture, goal: ThrowHealthPotGoal, value: Float) {
        // Health events are delivered before the client applies the packet to its player.
        goal.onEvent(EntityHealthUpdateEvent(value))
        f.selfData.health = value
    }

    private fun assertCombatRestored(f: SupplyTestFixture, goal: ThrowHealthPotGoal) {
        assertFalse(goal.executing, goal.debugSummary())
        assertEquals(1, f.selfData.heldSlot)
        assertTrue(f.buttons.isEmpty())
        assertTrue(f.movement.isEmpty())
        assertFalse(f.client.blocksContinuousAim)
        assertFalse(f.client.blocksContinuousAttack)
        assertFalse(f.client.blocksContinuousMovement)
    }

    @Test fun `full health after the first bottle cancels the second and releases controls`() {
        val f = fixture()
        val goal = firstThrow(f)
        consume(f)
        health(f, goal, 20f)
        advance(f, goal, 200)
        assertEquals(1, f.rightClicks)
        assertCombatRestored(f, goal)
        assertFalse(goal.checkExecute())
    }

    @Test fun `a full second bottle needs no further health rise or player hit to finish`() {
        val f = fixture()
        val goal = firstThrow(f)
        consume(f)
        health(f, goal, 12f)
        advance(f, goal, 2)
        assertEquals(2, f.rightClicks)
        consume(f)
        health(f, goal, 20f)
        advance(f, goal, 200)
        assertEquals(2, f.rightClicks)
        assertCombatRestored(f, goal)
        assertFalse(goal.checkExecute())
    }

    @Test fun `early late and merged healing packets never produce a third bottle`() {
        for (packetTiming in listOf("early", "late", "merged")) {
            val f = fixture()
            val goal = firstThrow(f)
            consume(f)
            if (packetTiming == "early") health(f, goal, 12f)
            advance(f, goal, 2)
            assertEquals(2, f.rightClicks, packetTiming)
            consume(f)
            advance(f, goal)
            if (packetTiming != "early") health(f, goal, 16f)
            advance(f, goal, 15)
            assertTrue(goal.executing, "Must retain the last bottle's recovery window: $packetTiming")
            assertTrue(Key.Type.KEY_W in f.movement)
            advance(f, goal, 200)
            assertEquals(2, f.rightClicks, packetTiming)
            assertCombatRestored(f, goal)
        }
    }

    @Test fun `damage during a normal single throw upgrades the same round to two bottles`() {
        val f = fixture(health = 10f)
        val goal = firstThrow(f)
        consume(f)
        health(f, goal, 4f)
        advance(f, goal, 2)
        assertEquals(2, f.rightClicks)
        consume(f)
        health(f, goal, 14f)
        advance(f, goal, 200)
        assertEquals(2, f.rightClicks)
        assertCombatRestored(f, goal)
    }

    @Test fun `a consumed single miss ends the round and preserves the normal cooldown`() {
        val f = fixture(health = 10f)
        val goal = firstThrow(f)
        val thrownTick = f.tick
        consume(f)
        advance(f, goal, 20)
        assertEquals(1, f.rightClicks)
        assertCombatRestored(f, goal)
        assertFalse(goal.checkExecute())
        f.tick = thrownTick + 40
        assertTrue(goal.checkExecute())
        goal.callEnd()
    }

    @Test fun `two consumed misses settle even under continuous damage without extra clicks`() {
        val f = fixture()
        val goal = firstThrow(f)
        consume(f)
        advance(f, goal, 2)
        consume(f)
        repeat(24) {
            if (goal.executing) health(f, goal, 4f - (it + 1) * 0.05f)
            advance(f, goal)
        }
        assertEquals(2, f.rightClicks)
        assertCombatRestored(f, goal)
        assertTrue(goal.checkExecute()) // A new emergency round is a separate health decision.
        goal.callEnd()
    }

    @Test fun `an ignored click retries its original bottle before any second bottle`() {
        val f = fixture()
        val goal = firstThrow(f)
        advance(f, goal, 15)
        assertEquals(1, f.rightClicks)
        advance(f, goal)
        assertEquals(2, f.rightClicks) // Retried input; no potion was spent by the first click.
        consume(f)
        advance(f, goal, 2)
        assertEquals(3, f.rightClicks)
        consume(f)
        advance(f, goal, 200)
        assertEquals(6, f.selfData.slots.getValue(0).count)
        assertEquals(3, f.rightClicks)
        assertCombatRestored(f, goal)
    }

    @Test fun `a health packet with delayed inventory confirmation never repeats the healed bottle`() {
        val f = fixture(health = 10f)
        val goal = firstThrow(f)
        health(f, goal, 16f)
        advance(f, goal, 200)
        assertEquals(1, f.rightClicks)
        assertCombatRestored(f, goal)
    }

    @Test fun `double throw can finish on one merged health packet without inventory confirmation`() {
        val f = fixture()
        val goal = firstThrow(f)
        consume(f)
        advance(f, goal, 2)
        health(f, goal, 16f)
        advance(f, goal, 200)
        assertEquals(2, f.rightClicks)
        assertCombatRestored(f, goal)
    }

    @Test fun `an exhausted inventory settles both a healed and a missed emergency bottle`() {
        for (healed in listOf(false, true)) {
            val f = fixture(potions = 1)
            val goal = firstThrow(f)
            consume(f)
            if (healed) health(f, goal, 12f)
            advance(f, goal, 200)
            assertEquals(1, f.rightClicks)
            assertCombatRestored(f, goal)
            assertFalse(goal.checkExecute())
        }
    }

    @Test fun `unstacked bottles in different hotbar slots share one round and escape direction`() {
        val f = fixture(potions = 1).apply {
            selfData.slots[2] = SupplyTestFixture.Stack(Item.POTION, 1, durability = 16421)
        }
        val goal = firstThrow(f)
        val escapeYaw = f.selfData.yaw
        consume(f)
        advance(f, goal, 4)
        assertEquals(2, f.rightClicks)
        assertEquals(2, f.selfData.heldSlot)
        assertEquals(escapeYaw, f.selfData.yaw)
        consume(f)
        advance(f, goal, 200)
        assertCombatRestored(f, goal)
    }

    @Test fun `simulated latency extends recovery without allowing replacement bottles`() {
        val f = fixture().apply { config.latency = 1000 }
        val goal = firstThrow(f)
        consume(f)
        advance(f, goal, 2)
        consume(f)
        advance(f, goal, 30)
        assertTrue(goal.executing)
        assertEquals(2, f.rightClicks)
        advance(f, goal, 20)
        assertEquals(2, f.rightClicks)
        assertCombatRestored(f, goal)
    }

    @Test fun `ignored input retries never reset the rounds total duration`() {
        val f = fixture()
        val goal = firstThrow(f)
        advance(f, goal, 200)
        assertFalse(goal.executing)
        assertEquals(160, goal.tickCount)
        assertTrue(f.rightClicks > 1)
        assertEquals(8, f.selfData.slots.getValue(0).count)
        assertTrue(f.buttons.isEmpty())
        assertTrue(f.movement.isEmpty())
    }

    @Test fun `health recovering during preparation cancels a queued potion`() {
        val f = fixture()
        val goal = ThrowHealthPotGoal(f.client)
        f.start(goal)
        advance(f, goal, 2)
        health(f, goal, 20f)
        advance(f, goal, 200)
        assertEquals(0, f.rightClicks)
        assertCombatRestored(f, goal)
    }

    @Test fun `difficulty aim delays still apply to the first bottle`() {
        for (difficulty in BotDifficulty.entries) {
            val f = fixture(health = 10f).apply { difficulty.applyTo(config) }
            val goal = ThrowHealthPotGoal(f.client)
            f.start(goal)
            advance(f, goal, 2)
            val alignedTick = f.tick + 1
            advance(f, goal, f.config.healthPotAimWaitTicks)
            assertEquals(0, f.rightClicks, difficulty.name)
            advance(f, goal)
            assertEquals(1, f.rightClicks, difficulty.name)
            assertEquals(alignedTick + f.config.healthPotAimWaitTicks, f.tick)
            goal.callEnd()
        }
    }

    @Test fun `pressure still defers a normal potion but never an emergency round`() {
        val f = fixture(health = 10f).apply { enemyData.health = 5f }
        val goal = ThrowHealthPotGoal(f.client)
        assertFalse(goal.checkExecute())
        f.selfData.health = 4f
        assertTrue(goal.checkExecute())
        goal.callEnd()
    }

    @Test fun `close preparation damage still yields to melee until emergency health`() {
        val f = fixture(health = 10f).apply { enemyData.x = 2.0 }
        val goal = ThrowHealthPotGoal(f.client)
        f.start(goal)
        advance(f, goal)
        health(f, goal, 8f)
        advance(f, goal, 20)
        assertEquals(0, f.rightClicks)
        assertFalse(f.client.blocksContinuousAim)
        assertFalse(f.client.blocksContinuousAttack)
        assertFalse(f.client.blocksContinuousMovement)
        health(f, goal, 4f)
        advance(f, goal, 5)
        assertEquals(1, f.rightClicks)
        assertTrue(f.client.blocksContinuousAttack)
        goal.callEnd()
    }
}
