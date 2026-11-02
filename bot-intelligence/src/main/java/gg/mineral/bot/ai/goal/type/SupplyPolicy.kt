package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.entity.effect.PotionEffectType
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.Inventory
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.inv.item.ItemStack
import kotlin.math.ceil
import kotlin.math.max

internal enum class SupplyKind { FOOD, POTION, STRENGTH }
internal enum class SupplyOutcome { IN_PROGRESS, COMPLETED, INTERRUPTED, TIMED_OUT }
internal enum class SupplyWindow { UNSAFE, NATURAL, OPPONENT_SUPPLY }

internal data class SupplyItem(val id: Int, val variant: Int) {
    fun matches(stack: ItemStack?): Boolean =
        stack != null && stack.count > 0 && stack.item.id == id && stack.durability == variant

    fun count(inventory: Inventory): Int = (0..35).sumOf { slot ->
        inventory.getItemStackAt(slot)?.takeIf(::matches)?.count ?: 0
    }

    fun findSlot(inventory: Inventory): Int = (0..35).firstOrNull { matches(inventory.getItemStackAt(it)) } ?: -1

    companion object {
        fun from(stack: ItemStack) = SupplyItem(stack.item.id, stack.durability)
    }
}

internal object SupplyItems {
    fun findNeededSlot(client: ClientInstance, kind: SupplyKind): Int =
        (0..35).firstOrNull { slot ->
            client.fakePlayer.inventory.getItemStackAt(slot)?.let { needed(client, kind, it) } == true
        } ?: -1

    fun needed(client: ClientInstance, kind: SupplyKind, stack: ItemStack): Boolean {
        if (stack.count <= 0) return false
        val player = client.fakePlayer
        if (kind == SupplyKind.FOOD) return player.hunger < 20 && Item.Type.FOOD.isType(stack.item.id)
        if (stack.item.id != Item.POTION) return false
        val potion = stack.potion ?: return false
        if (potion.isSplash || potion.effects.isEmpty()) return false
        return potion.effects.any { effect ->
            (kind != SupplyKind.STRENGTH || effect.potionID == PotionEffectType.INCREASE_DAMAGE.id) &&
                (player.clientActivePotionEffects.firstOrNull { it.potionID == effect.potionID }
                    ?.duration?.let { it <= 30 * 20 } ?: true)
        }
    }

    fun hasHealingSplash(client: ClientInstance): Boolean = (0..35).any { slot ->
        val stack = client.fakePlayer.inventory.getItemStackAt(slot)
        stack != null && stack.count > 0 && stack.item.id == Item.POTION &&
            stack.potion?.let { potion -> potion.isSplash &&
                potion.effects.any { it.potionID == PotionEffectType.INSTANT_HEAL.id } } == true
    }
}

internal object SupplyPolicy {
    const val USE_TICKS = 32
    const val RETREAT_TICKS = 40
    const val RETREAT_RETRY_TICKS = 60
    const val STRIKE_WAIT_TICKS = 6

    fun latencyTicks(latencyMillis: Int): Int = ceil(max(0, latencyMillis) / 50.0).toInt()
    fun synchronizationTicks(latencyMillis: Int): Int = max(10, latencyTicks(latencyMillis) + 4)

    fun window(snapshot: CombatPerception.Snapshot, reach: Double, latencyMillis: Int): SupplyWindow {
        val nearest = snapshot.enemies.minByOrNull { it.distance2D } ?: return SupplyWindow.NATURAL
        val budget = USE_TICKS + 4 + latencyTicks(latencyMillis)
        val synchronizing = nearest.eatingOrDrinking && nearest.lineOfSightLikelyClear
        val othersSafe = snapshot.enemies.all { enemy ->
            (synchronizing && enemy.uuid == nearest.uuid) || naturallySafe(snapshot, enemy, reach, budget)
        }
        return when {
            !othersSafe -> SupplyWindow.UNSAFE
            synchronizing -> SupplyWindow.OPPONENT_SUPPLY
            else -> SupplyWindow.NATURAL
        }
    }

    private fun naturallySafe(snapshot: CombatPerception.Snapshot, enemy: CombatPerception.PlayerState,
                              reach: Double, budget: Int): Boolean {
        if (enemy.distance2D < 6.0) return false
        // Do not project the bot's pre-use sprint velocity into the eating/drinking interval.
        val dx = snapshot.self.x - enemy.x
        val dz = snapshot.self.z - enemy.z
        val closingSpeed = max(0.0, (dx * enemy.velocityX + dz * enemy.velocityZ) / max(enemy.distance2D, 0.001))
        return enemy.distance2D - closingSpeed * budget > reach + 0.5
    }

    fun underClosePressure(snapshot: CombatPerception.Snapshot, reach: Double): Boolean =
        snapshot.enemies.any { enemy ->
            !enemy.eatingOrDrinking && enemy.distance2D <= reach + 0.5 &&
                (enemy.pressuringSelf || enemy.holdingWeapon || enemy.movingTowardSelf)
        }
}

/** Shared by a kit's food and drink goals, including generic and strength-only drinks. */
internal class SupplyState {
    private var readyTick = Int.MIN_VALUE
    private var retreatReadyTick = Int.MIN_VALUE

    fun ready(tick: Int): Boolean = tick >= readyTick
    fun retreatReady(tick: Int): Boolean = tick >= retreatReadyTick
    fun retreatFailed(tick: Int) { retreatReadyTick = tick + SupplyPolicy.RETREAT_RETRY_TICKS }
    fun ended(tick: Int, outcome: SupplyOutcome, latencyMillis: Int) {
        readyTick = max(readyTick, tick + if (outcome == SupplyOutcome.COMPLETED)
            SupplyPolicy.synchronizationTicks(latencyMillis) else 20)
    }
}

/** A missing use transition is a timeout; an early transition is an interruption, not success. */
internal class SupplyUseSession(val startedTick: Int, private val initialCount: Int, latencyMillis: Int) {
    private var observedUse = false
    private val timeoutTicks = SupplyPolicy.USE_TICKS + 6 + SupplyPolicy.latencyTicks(latencyMillis)

    fun remaining(tick: Int): Int = max(0, SupplyPolicy.USE_TICKS - (tick - startedTick))

    fun observe(tick: Int, count: Int, using: Boolean): SupplyOutcome {
        if (count < initialCount) return SupplyOutcome.COMPLETED
        if (using) observedUse = true
        val elapsed = tick - startedTick
        if (observedUse && !using) return if (elapsed >= SupplyPolicy.USE_TICKS)
            SupplyOutcome.COMPLETED else SupplyOutcome.INTERRUPTED
        if (elapsed >= timeoutTicks) return SupplyOutcome.TIMED_OUT
        return SupplyOutcome.IN_PROGRESS
    }
}
