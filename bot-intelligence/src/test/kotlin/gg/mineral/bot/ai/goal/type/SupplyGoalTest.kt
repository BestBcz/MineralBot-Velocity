package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.ai.goal.DrinkPotionGoal
import gg.mineral.bot.ai.goal.DrinkStrengthPotionGoal
import gg.mineral.bot.ai.goal.MeleeCombatGoal
import gg.mineral.bot.ai.goal.ThrowPearlGoal
import gg.mineral.bot.ai.goal.practice.PracticeAI
import gg.mineral.bot.ai.goal.practice.SafeEatGoal
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.effect.PotionEffectType
import gg.mineral.bot.api.event.entity.EntityHurtEvent
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.inv.item.Item
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SupplyGoalTest {
    private fun fixture() = SupplyTestFixture().apply {
        selfData.slots[0] = SupplyTestFixture.Stack(Item.COOKED_BEEF)
        selfData.slots[1] = SupplyTestFixture.Stack(Item.DIAMOND_SWORD, count = 1, attackDamage = 7.0)
    }

    private fun running(goal: Goal) = (goal as Sporadic).executing
    private fun stop(goal: Goal) = (goal as Sporadic).callEnd()

    @Test fun `one food completes before full hunger and releases all owned controls`() {
        val f = fixture().apply { selfData.hunger = 3f }
        val goal = SafeEatGoal(f.client)
        f.startUsing(goal)
        f.selfData.slots.getValue(0).count--
        f.step(goal)
        assertFalse(running(goal))
        assertEquals(1, f.rightClicks)
        assertFalse(f.selfData.consuming)
        assertTrue(f.buttons.isEmpty())
        assertTrue(f.movement.isEmpty())
        assertFalse(goal.checkExecute())
        f.tick += 10
        assertTrue(goal.checkExecute())
        stop(goal)
    }

    @Test fun `clearing attacks before holding use preserves pending retreat steering`() {
        val f = fixture()
        val goal = SafeEatGoal(f.client)
        f.startUsing(goal)
        assertNotNull(f.yawAtUse.single())
        stop(goal)
    }

    @Test fun `all three goals stop on a local normal finish without stat packets`() {
        for (kind in SupplyKind.entries) {
            val f = fixture()
            val goal: Goal = when (kind) {
                SupplyKind.FOOD -> SafeEatGoal(f.client)
                SupplyKind.POTION -> DrinkPotionGoal(f.client)
                SupplyKind.STRENGTH -> DrinkStrengthPotionGoal(f.client)
            }
            if (kind != SupplyKind.FOOD) f.selfData.slots[0] = f.potion(PotionEffectType.INCREASE_DAMAGE.id)
            f.startUsing(goal)
            f.tick += 32
            f.selfData.consuming = false
            goal.callGameLoop()
            assertFalse(running(goal), kind.name)
            assertEquals(1, f.rightClicks)
            assertTrue(f.buttons.isEmpty())
            assertTrue(f.movement.isEmpty())
        }
    }

    @Test fun `full hunger and long effects do not interrupt combat for opponent supplies`() {
        val f = fixture().apply {
            selfData.hunger = 20f
            selfData.slots[2] = potion(PotionEffectType.INCREASE_DAMAGE.id)
            selfData.effects.add(SupplyTestFixture.Effect(PotionEffectType.INCREASE_DAMAGE.id, 601))
        }
        assertFalse(SafeEatGoal(f.client).checkExecute())
        assertFalse(DrinkPotionGoal(f.client).checkExecute())
        assertFalse(DrinkStrengthPotionGoal(f.client).checkExecute())
        assertEquals(0, f.rightClicks)
    }

    @Test fun `close opponent receives at most one setup attack before switching to food`() {
        val f = fixture().apply { enemyData.x = 2.8; selfData.heldSlot = 1 }
        val goal = SafeEatGoal(f.client)
        f.startUsing(goal)
        assertEquals(1, f.leftClicks)
        assertTrue(f.inputOrder.indexOf("attack") < f.inputOrder.indexOf("slot:0"))
        assertTrue(f.inputOrder.indexOf("slot:0") < f.inputOrder.indexOf("use"))
        repeat(5) { f.step(goal); goal.callGameLoop() }
        assertEquals(1, f.leftClicks)
        assertEquals(1, f.rightClicks)
        stop(goal)
    }

    @Test fun `after one strike a stopped fake eat returns to combat`() {
        val f = fixture().apply { enemyData.x = 2.8; selfData.heldSlot = 1 }
        val goal = SafeEatGoal(f.client)
        f.start(goal)
        repeat(6) { if (f.leftClicks == 0) f.step(goal) }
        assertEquals(1, f.leftClicks)
        f.enemyData.consuming = false
        f.step(goal)
        assertFalse(running(goal))
        assertEquals(0, f.rightClicks)
        assertTrue(f.buttons.isEmpty())
    }

    @Test fun `a new enemy invalidates the window after the setup strike`() {
        val f = fixture().apply { enemyData.x = 2.8; selfData.heldSlot = 1 }
        val goal = SafeEatGoal(f.client)
        f.start(goal)
        repeat(6) { if (f.leftClicks == 0) f.step(goal) }
        f.extraData.x = 2.0
        f.entities.add(f.extra)
        f.step(goal)
        assertFalse(running(goal))
        assertEquals(1, f.leftClicks)
        assertEquals(0, f.rightClicks)
    }

    @Test fun `strike preparation never chases a departing opponent`() {
        val f = fixture().apply { enemyData.x = 2.8; selfData.heldSlot = 1; selfData.yaw = 90f }
        val goal = SafeEatGoal(f.client)
        f.start(goal)
        f.step(goal)
        assertFalse(Key.Type.KEY_W in f.movement)
        f.enemyData.x = 7.0
        f.step(goal)
        f.step(goal)
        f.step(goal)
        assertEquals(0, f.leftClicks)
        assertEquals(1, f.rightClicks)
        stop(goal)
    }

    @Test fun `early pressure interrupts with backoff but final eight ticks finish`() {
        for (elapsed in listOf(8, 24)) {
            val f = fixture()
            val goal = SafeEatGoal(f.client)
            f.startUsing(goal)
            f.tick += elapsed
            f.enemyData.x = 3.0
            f.enemyData.consuming = false
            goal.callTick()
            assertEquals(elapsed == 24, running(goal), "elapsed=$elapsed")
            if (elapsed == 8) {
                assertTrue(f.buttons.isEmpty())
                f.enemyData.consuming = true
                f.tick += 19
                assertFalse(goal.checkExecute())
                f.tick++
                assertTrue(goal.checkExecute())
            }
            stop(goal)
        }
    }

    @Test fun `setup aim is bounded to six ticks even when the bot cannot turn in time`() {
        val f = fixture().apply {
            enemyData.x = 2.8
            selfData.heldSlot = 1
            selfData.yaw = 90f
            config.horizontalAimSpeed = 0.05f
        }
        val goal = SafeEatGoal(f.client)
        f.startUsing(goal)
        assertEquals(0, f.leftClicks)
        assertEquals(1, f.rightClicks)
        stop(goal)
    }

    @Test fun `locked drink does not switch to another potion when effects arrive early`() {
        val f = fixture().apply {
            selfData.slots[0] = potion(PotionEffectType.SPEED.id)
            selfData.slots[2] = potion(PotionEffectType.INCREASE_DAMAGE.id)
        }
        val goal = DrinkPotionGoal(f.client)
        f.startUsing(goal)
        f.selfData.effects.add(SupplyTestFixture.Effect(PotionEffectType.SPEED.id, 3600))
        f.step(goal)
        assertTrue(running(goal))
        assertEquals(0, f.selfData.heldSlot)
        assertEquals(1, f.rightClicks)
        f.selfData.slots.getValue(0).count--
        f.step(goal)
        assertFalse(running(goal))
        assertEquals(3, f.selfData.slots.getValue(2).count)
    }

    @Test fun `emergency healing interrupts even near completion`() {
        val f = fixture()
        val goal = SafeEatGoal(f.client)
        f.startUsing(goal)
        f.tick += 28
        f.selfData.health = 5f
        f.selfData.slots[3] = f.potion(PotionEffectType.INSTANT_HEAL.id, splash = true)
        goal.callTick()
        assertFalse(running(goal))
        assertTrue(f.buttons.isEmpty())
        assertTrue(f.movement.isEmpty())
    }

    @Test fun `only hungry bots create spacing and failed retreats are bounded`() {
        val f = fixture().apply { enemyData.consuming = false; selfData.hunger = 13f }
        val goal = SafeEatGoal(f.client)
        assertFalse(goal.checkExecute())
        f.selfData.hunger = 12f
        f.start(goal)
        f.step(goal)
        assertTrue(Key.Type.KEY_W in f.movement)
        assertEquals(0, f.rightClicks)
        repeat(40) { if (running(goal)) f.step(goal) }
        assertFalse(running(goal))
        assertTrue(f.movement.isEmpty())
        f.tick += 59
        assertFalse(goal.checkExecute())
        f.tick++
        assertTrue(goal.checkExecute())
        stop(goal)
    }

    @Test fun `natural windows remain usable during retreat retry backoff`() {
        val f = fixture().apply { enemyData.consuming = false; selfData.hunger = 12f }
        val goal = SafeEatGoal(f.client)
        f.start(goal)
        repeat(41) { if (running(goal)) f.step(goal) }
        f.enemyData.x = 7.0
        f.tick++
        f.startUsing(goal)
        assertEquals(1, f.rightClicks)
        stop(goal)
    }

    @Test fun `retreat switches to use as soon as a window opens`() {
        val f = fixture().apply { enemyData.consuming = false; selfData.hunger = 12f }
        val goal = SafeEatGoal(f.client)
        f.start(goal)
        f.step(goal)
        f.enemyData.x = 7.0
        f.step(goal)
        assertEquals(1, f.rightClicks)
        stop(goal)
    }

    @Test fun `walls lava and missing floor abort retreat without holding movement`() {
        for (terrain in listOf("wall", "lava", "drop")) {
            val f = fixture().apply { this.terrain = terrain; enemyData.consuming = false; selfData.hunger = 12f }
            val goal = SafeEatGoal(f.client)
            f.start(goal)
            f.step(goal)
            assertFalse(running(goal), terrain)
            assertEquals(0, f.rightClicks)
            assertTrue(f.movement.isEmpty())
        }
    }

    @Test fun `kit drink goals share latency grace before reusing either drink or food`() {
        val f = fixture().apply {
            selfData.slots[0] = potion(PotionEffectType.INCREASE_DAMAGE.id)
            latency = 1000
        }
        val goals = PracticeAI.getGoalsForKit(f.client, "Gapple")
        val drink = goals.filterIsInstance<DrinkPotionGoal>().single()
        val strength = goals.filterIsInstance<DrinkStrengthPotionGoal>().single()
        val food = goals.filterIsInstance<SafeEatGoal>().single()
        f.startUsing(drink)
        f.selfData.slots.getValue(0).count--
        f.step(drink)
        f.selfData.slots[2] = SupplyTestFixture.Stack(Item.COOKED_BEEF)
        f.tick += 23
        assertFalse(strength.checkExecute())
        assertFalse(food.checkExecute())
        f.tick++
        assertTrue(strength.checkExecute())
        stop(strength)
    }

    @Test fun `food windows suppress chase pearls but preserve emergency escape`() {
        val f = fixture().apply {
            enemyData.x = 8.0
            selfData.slots[4] = SupplyTestFixture.Stack(Item.ENDER_PEARL)
        }
        assertFalse(ThrowPearlGoal(f.client).checkExecute())
        f.selfData.hunger = 20f
        val chase = ThrowPearlGoal(f.client)
        assertTrue(chase.checkExecute())
        stop(chase)
        f.selfData.hunger = 19f
        f.enemyData.x = 3.0
        f.enemyData.consuming = false
        f.enemyData.sprinting = true
        f.enemyData.slots[0] = SupplyTestFixture.Stack(Item.DIAMOND_SWORD, attackDamage = 7.0)
        val escape = ThrowPearlGoal(f.client)
        assertFalse(escape.checkExecute())
        f.tick += 100
        assertTrue(escape.checkExecute())
        stop(escape)
    }

    @Test fun `melee yields attacks and sprint reset then resumes after one portion`() {
        val f = fixture().apply { enemyData.x = 2.8; selfData.heldSlot = 1 }
        val food = SafeEatGoal(f.client)
        val melee = MeleeCombatGoal(f.client)
        f.startUsing(food)
        melee.callTick()
        val keys = f.movement.toSet()
        melee.onEvent(EntityHurtEvent(f.enemy))
        assertEquals(keys, f.movement)
        melee.callGameLoop()
        assertEquals(1, f.leftClicks)
        f.selfData.slots.getValue(0).count--
        f.step(food)
        assertFalse(running(food))
        melee.callTick()
        melee.callGameLoop()
        assertEquals(1, f.selfData.heldSlot)
        assertEquals(2, f.leftClicks)
        assertFalse(MouseButton.Type.RIGHT_CLICK in f.buttons)
    }
}
