package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.entity.effect.PotionEffectType
import gg.mineral.bot.api.inv.item.Item
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SupplyPolicyTest {
    private fun window(f: SupplyTestFixture): SupplyWindow =
        SupplyPolicy.window(CombatPerception(f.client).snapshot(), 3.0, f.latency)

    @Test fun `any missing hunger qualifies but full hunger does not`() {
        val f = SupplyTestFixture()
        f.selfData.slots[0] = SupplyTestFixture.Stack(Item.COOKED_BEEF)
        assertEquals(0, SupplyItems.findNeededSlot(f.client, SupplyKind.FOOD))
        f.selfData.hunger = 20f
        assertEquals(-1, SupplyItems.findNeededSlot(f.client, SupplyKind.FOOD))
    }

    @Test fun `food selection does not consume either kind of golden apple`() {
        val f = SupplyTestFixture()
        f.selfData.slots[0] = SupplyTestFixture.Stack(Item.GOLDEN_APPLE)
        f.selfData.slots[1] = SupplyTestFixture.Stack(Item.GOLDEN_APPLE, durability = 1)
        assertEquals(-1, SupplyItems.findNeededSlot(f.client, SupplyKind.FOOD))
    }

    @Test fun `both drink goals respect missing effects and the thirty second boundary`() {
        val f = SupplyTestFixture()
        f.selfData.slots[0] = f.potion(PotionEffectType.INCREASE_DAMAGE.id)
        for (kind in listOf(SupplyKind.POTION, SupplyKind.STRENGTH)) {
            assertEquals(0, SupplyItems.findNeededSlot(f.client, kind))
            f.selfData.effects.add(SupplyTestFixture.Effect(PotionEffectType.INCREASE_DAMAGE.id, 601))
            assertEquals(-1, SupplyItems.findNeededSlot(f.client, kind))
            f.selfData.effects.clear()
            f.selfData.effects.add(SupplyTestFixture.Effect(PotionEffectType.INCREASE_DAMAGE.id, 600))
            assertEquals(0, SupplyItems.findNeededSlot(f.client, kind))
            f.selfData.effects.clear()
        }
        f.selfData.slots[0] = f.potion(PotionEffectType.INCREASE_DAMAGE.id, splash = true)
        assertEquals(-1, SupplyItems.findNeededSlot(f.client, SupplyKind.POTION))
    }

    @Test fun `a close consuming opponent creates a window without relaxing other threats`() {
        val f = SupplyTestFixture()
        f.enemyData.x = 2.5
        assertEquals(SupplyWindow.OPPONENT_SUPPLY, window(f))
        f.extraData.x = 3.0
        f.entities.add(f.extra)
        assertEquals(SupplyWindow.UNSAFE, window(f))
        f.extraData.x = 10.0
        f.extraData.velocityX = -0.4
        assertEquals(SupplyWindow.UNSAFE, window(f))
        f.extraData.velocityX = 0.0
        assertEquals(SupplyWindow.OPPONENT_SUPPLY, window(f))
    }

    @Test fun `a distant consuming enemy cannot hide a closer attacking enemy`() {
        val f = SupplyTestFixture()
        f.enemyData.x = 10.0
        f.extraData.x = 3.0
        f.entities.add(f.extra)
        assertEquals(SupplyWindow.UNSAFE, window(f))
    }

    @Test fun `natural windows require six horizontal blocks and time to complete`() {
        val f = SupplyTestFixture()
        f.enemyData.consuming = false
        f.enemyData.x = 5.99
        assertEquals(SupplyWindow.UNSAFE, window(f))
        f.enemyData.x = 6.0
        assertEquals(SupplyWindow.NATURAL, window(f))
        f.enemyData.x = 10.0
        f.enemyData.velocityX = -0.4
        assertEquals(SupplyWindow.UNSAFE, window(f))
        f.enemyData.x = 20.0
        assertEquals(SupplyWindow.NATURAL, window(f))
        f.latency = 500
        assertEquals(SupplyWindow.UNSAFE, window(f))
    }

    @Test fun `sideways or retreating movement does not count as closing speed`() {
        val f = SupplyTestFixture()
        f.enemyData.consuming = false
        f.enemyData.x = 6.0
        f.enemyData.velocityZ = 0.5
        assertEquals(SupplyWindow.NATURAL, window(f))
        f.enemyData.velocityX = 0.5
        assertEquals(SupplyWindow.NATURAL, window(f))
    }

    @Test fun `bot sprint velocity and long waiting do not make pressure safe`() {
        val f = SupplyTestFixture()
        f.enemyData.consuming = false
        f.enemyData.x = 10.0
        f.enemyData.velocityX = -0.4
        f.selfData.velocityX = -1.0
        assertEquals(SupplyWindow.UNSAFE, window(f))
        f.tick += 2000
        assertEquals(SupplyWindow.UNSAFE, window(f))
    }

    @Test fun `occlusion alone never creates a close window`() {
        val f = SupplyTestFixture()
        f.occluded = true
        assertEquals(SupplyWindow.UNSAFE, window(f))
        f.entities.clear()
        assertEquals(SupplyWindow.NATURAL, window(f))
    }

    @Test fun `item counts survive moving slots and distinguish potion variants`() {
        val f = SupplyTestFixture()
        val stack = f.potion(PotionEffectType.SPEED.id)
        f.selfData.slots[0] = stack
        f.selfData.slots[1] = f.potion(PotionEffectType.INCREASE_DAMAGE.id)
        val key = SupplyItem.from(stack)
        assertEquals(3, key.count(f.self.inventory))
        f.selfData.slots.remove(0)
        f.selfData.slots[20] = stack
        assertEquals(3, key.count(f.self.inventory))
        assertEquals(20, key.findSlot(f.self.inventory))
    }

    @Test fun `a single count decrease finishes even with more food and use still held`() {
        val use = SupplyUseSession(100, 3, 0)
        assertEquals(SupplyOutcome.IN_PROGRESS, use.observe(101, 3, true))
        assertEquals(SupplyOutcome.COMPLETED, use.observe(132, 2, true))
    }

    @Test fun `local use ending distinguishes interruption and completion`() {
        val interrupted = SupplyUseSession(100, 3, 0)
        interrupted.observe(101, 3, true)
        assertEquals(SupplyOutcome.INTERRUPTED, interrupted.observe(110, 3, false))
        val completed = SupplyUseSession(100, 3, 0)
        completed.observe(101, 3, true)
        assertEquals(SupplyOutcome.COMPLETED, completed.observe(132, 3, false))
    }

    @Test fun `missing use state times out without reporting a successful consumption`() {
        val use = SupplyUseSession(100, 3, 0)
        assertEquals(SupplyOutcome.IN_PROGRESS, use.observe(137, 3, false))
        assertEquals(SupplyOutcome.TIMED_OUT, use.observe(138, 3, false))
    }

    @Test fun `shared cooldown covers synchronization cancellation and failed retreats`() {
        val state = SupplyState()
        state.ended(100, SupplyOutcome.COMPLETED, 0)
        assertFalse(state.ready(109))
        assertTrue(state.ready(110))
        state.ended(200, SupplyOutcome.COMPLETED, 1000)
        assertFalse(state.ready(223))
        assertTrue(state.ready(224))
        state.ended(300, SupplyOutcome.INTERRUPTED, 0)
        assertFalse(state.ready(319))
        assertTrue(state.ready(320))
        state.retreatFailed(400)
        assertFalse(state.retreatReady(459))
        assertTrue(state.retreatReady(460))
    }
}
