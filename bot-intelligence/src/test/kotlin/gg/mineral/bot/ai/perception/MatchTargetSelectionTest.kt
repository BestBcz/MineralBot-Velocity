package gg.mineral.bot.ai.perception

import gg.mineral.bot.api.entity.living.player.ClientPlayer
import gg.mineral.bot.api.instance.CombatTargetState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class MatchTargetSelectionTest {
    private val self = player(0, 0.0)
    private val nearby = player(1, 2.0)
    private val assigned = player(2, 5.0)

    private fun player(id: Long, distance: Double, score: Double = distance, visible: Boolean = true,
                       approaching: Boolean = false, health: Float = 20f): CombatPerception.PlayerState {
        val uuid = UUID(0, id)
        val entity = Proxy.newProxyInstance(ClientPlayer::class.java.classLoader,
            arrayOf(ClientPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "getUuid" -> uuid
                else -> error("Unexpected entity access: ${method.name}")
            }
        } as ClientPlayer
        return CombatPerception.PlayerState(
            entity = entity, uuid = uuid, username = "Player$id", tick = 0,
            x = distance, y = 64.0, z = 0.0, yaw = 0f, pitch = 0f,
            velocityX = 0.0, velocityY = 0.0, velocityZ = 0.0, horizontalSpeed = 0.0,
            health = health, hunger = 20f, eatingOrDrinking = false,
            onGround = true, sprinting = false, activePotionEffectIds = emptySet(),
            heldItemId = null, heldAttackDamage = 0.0,
            distance2D = distance, distance3D = distance, aimErrorToSelf = 0f,
            lookingAtSelf = false, movingTowardSelf = approaching,
            lineOfSightLikelyClear = visible, targetScore = score
        )
    }

    private fun snapshot(tick: Int, state: CombatTargetState, vararg enemies: CombatPerception.PlayerState,
                         preferred: UUID? = assigned.uuid) =
        CombatPerception.Snapshot(tick, self, enemies.toList(), emptyList(), preferred, state, 16.0)

    @Test fun `assignment is a small preference instead of a permanent lock`() {
        assertSame(nearby, snapshot(0, CombatTargetState(), assigned, nearby).bestTargetState(null, 16.0))
        val similar = player(1, 4.8)
        assertSame(assigned, snapshot(0, CombatTargetState(), similar, assigned).bestTargetState(null, 16.0))
    }

    @Test fun `ordinary switches respect ten ticks of cooldown`() {
        val state = CombatTargetState()
        val current = player(2, 4.0)
        snapshot(0, state, current).bestTargetState(null, 16.0)
        for (tick in 1..9) {
            assertSame(current, snapshot(tick, state, nearby, current).bestTargetState(null, 16.0))
        }
        assertSame(nearby, snapshot(10, state, current, nearby).bestTargetState(null, 16.0))
        assertEquals(10, state.lastSwitchTick)
    }

    @Test fun `score margin prevents oscillation when players trade small distance advantages`() {
        val state = CombatTargetState()
        val current = player(1, 3.0)
        snapshot(0, state, current, preferred = null).bestTargetState(null, 16.0)
        for (tick in 1..80) {
            val challenger = player(2, if (tick % 2 == 0) 2.7 else 3.1)
            assertSame(current, snapshot(tick, state, challenger, current, preferred = null).bestTargetState(null, 16.0))
        }
        val better = player(2, 1.9)
        assertSame(better, snapshot(81, state, better, current, preferred = null).bestTargetState(null, 16.0))
    }

    @Test fun `nearby pressure overrides cooldown while the current target is distant`() {
        val state = CombatTargetState()
        snapshot(0, state, assigned).bestTargetState(null, 16.0)
        val distant = player(2, 8.0)
        val threat = player(1, 3.0, approaching = true)
        assertSame(threat, snapshot(1, state, distant, threat).bestTargetState(null, 16.0))
    }

    @Test fun `visible pressure can replace an occluded current target even with a worse raw score`() {
        val state = CombatTargetState()
        val blocked = player(2, 2.0, visible = false)
        snapshot(0, state, blocked).bestTargetState(null, 16.0)
        val threat = player(1, 3.0, approaching = true)
        assertSame(threat, snapshot(1, state, blocked, threat).bestTargetState(null, 16.0))
    }

    @Test fun `missing friendly dead or out of range target is replaced immediately`() {
        for (invalid in listOf(null, player(2, 30.0), player(2, 3.0, health = 0f))) {
            val state = CombatTargetState()
            snapshot(0, state, assigned).bestTargetState(null, 16.0)
            val enemies = listOfNotNull(invalid, nearby).toTypedArray()
            assertSame(nearby, snapshot(1, state, *enemies).bestTargetState(assigned.entity, 16.0))
        }
    }

    @Test fun `multiple goals see the same selection in a tick`() {
        val state = CombatTargetState()
        assertSame(nearby, snapshot(0, state, assigned, nearby).bestTargetState(null, 16.0))
        assertSame(nearby, snapshot(0, state, assigned, nearby).bestTargetState(assigned.entity, 16.0))
    }

    @Test fun `utility range does not change the shared combat target`() {
        val state = CombatTargetState()
        val farther = player(2, 6.0, score = 0.0)
        val view = snapshot(0, state, nearby, farther)
        assertSame(farther, view.bestTargetState(null, 16.0))
        assertNull(view.bestTargetState(null, 3.0))
        assertEquals(farther.uuid, state.targetUuid)
    }

    @Test fun `separate bots keep independent target state and initial assignments`() {
        val first = player(1, 4.0)
        val second = player(2, 4.0)
        val stateA = CombatTargetState()
        val stateB = CombatTargetState()
        assertSame(first, snapshot(0, stateA, second, first, preferred = first.uuid).bestTargetState(null, 16.0))
        assertSame(second, snapshot(0, stateB, first, second).bestTargetState(null, 16.0))
        stateA.reset()
        assertEquals(second.uuid, stateB.targetUuid)
    }

    @Test fun `no entity is fabricated for a missing assignment and self is never an opponent`() {
        val view = snapshot(0, CombatTargetState(), self, nearby)
        assertSame(nearby, view.bestTargetState(self.entity, 16.0))
        assertNull(view.stateFor(assigned.entity))
        assertNull(snapshot(1, view.targetState).bestTargetState(nearby.entity, 16.0))
        assertNull(view.targetState.targetUuid)
    }

    @Test fun `equal scores have stable ordering independent of entity iteration order`() {
        val first = player(1, 4.0)
        val second = player(2, 4.0)
        assertSame(first, snapshot(0, CombatTargetState(), second, first, preferred = null).bestTargetState(null, 16.0))
        assertSame(first, snapshot(0, CombatTargetState(), first, second, preferred = null).bestTargetState(null, 16.0))
    }
}
