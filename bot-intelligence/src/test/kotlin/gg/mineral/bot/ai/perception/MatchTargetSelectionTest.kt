package gg.mineral.bot.ai.perception

import gg.mineral.bot.api.entity.living.player.ClientPlayer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class MatchTargetSelectionTest {
    private val self = playerState(0, 0.0)
    private val nearby = playerState(1, 2.0)
    private val assigned = playerState(2, 5.0)

    private fun playerState(id: Long, distance: Double): CombatPerception.PlayerState {
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
            health = 20f, hunger = 20f, eatingOrDrinking = false,
            onGround = true, sprinting = false, activePotionEffectIds = emptySet(),
            heldItemId = null, heldAttackDamage = 0.0,
            distance2D = distance, distance3D = distance, aimErrorToSelf = 0f,
            lookingAtSelf = false, movingTowardSelf = false,
            lineOfSightLikelyClear = true, targetScore = distance
        )
    }

    @Test fun `match assignment takes priority over a closer current target`() {
        val snapshot = CombatPerception.Snapshot(0, self, listOf(nearby, assigned), emptyList(), assigned.uuid)
        assertSame(assigned, snapshot.bestTargetState(nearby.entity, 16.0))
    }

    @Test fun `missing assigned entity uses real visible opponents without fabricating it`() {
        val snapshot = CombatPerception.Snapshot(0, self, listOf(nearby), emptyList(), assigned.uuid)
        assertSame(nearby, snapshot.bestTargetState(null, 16.0))
        assertNull(snapshot.stateFor(assigned.entity))
    }

    @Test fun `out of range assignment falls back to the current reachable opponent`() {
        val snapshot = CombatPerception.Snapshot(0, self, listOf(assigned, nearby), emptyList(), assigned.uuid)
        assertSame(nearby, snapshot.bestTargetState(nearby.entity, 3.0))
        assertNull(snapshot.bestTargetState(null, 1.0))
    }

    @Test fun `without an assignment a valid current target remains selected`() {
        val snapshot = CombatPerception.Snapshot(0, self, listOf(nearby, assigned), emptyList(), null)
        assertSame(assigned, snapshot.bestTargetState(assigned.entity, 16.0))
        assertSame(nearby, snapshot.bestTargetState(null, 16.0))
    }
}
