package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.findBestMeleeWeaponSlot
import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

class FishingRodGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound {
    override var executing = false
    override var startTime: Long = 0
    override val maxDuration: Long = 40
    private val perception = CombatPerception(clientInstance)
    private val targetMotion = RodTargetMotion()
    private var lastRodTick = -100
    private var retryAfterTick = -1
    private var castTick = -1
    private var returnTick = -1
    private var aimTick = -1
    private var targetId = -1
    private var castYaw = 0f
    private var castPitch = 0f
    private var flightTicks = 0.0
    private var returning = false

    override fun shouldExecute(): Boolean {
        val config = clientInstance.configuration
        val enemy = perception.bestTarget() ?: return false
        sampleMotion(enemy)
        if (clientInstance.currentTick < retryAfterTick) return false
        if (clientInstance.currentTick - lastRodTick < config.rodCooldownTicks) return false
        if (!clientInstance.fakePlayer.inventory.contains(Item.FISHING_ROD)) return false
        return enemy.distance3D > config.rodMinRange &&
            enemy.distance3D <= config.rodMaxRange && enemy.lineOfSightLikelyClear
    }

    private fun sampleMotion(enemy: CombatPerception.PlayerState): RodTargetMotion.Velocity =
        targetMotion.sample(enemy.entity.entityId, clientInstance.currentTick, enemy.x, enemy.y, enemy.z,
            if (enemy.uuid == clientInstance.guidedTargetUuid) RodTargetMotion.Velocity(
                enemy.entity.motionX, enemy.entity.motionY, enemy.entity.motionZ) else null)

    override fun onStart() {
        clientInstance.mouse.clearPendingClicks()
        clientInstance.keyboard.stopAll()
        clientInstance.fakePlayer.stopUsingItem()
        castTick = -1
        returnTick = -1
        aimTick = -1
        returning = false
        targetId = perception.bestTarget()?.entity?.entityId ?: -1
    }

    private fun restoreMelee(): Boolean {
        val inventory = clientInstance.fakePlayer.inventory
        val slot = findBestMeleeWeaponSlot(36) { inventory.getItemStackAt(it)?.attackDamage } ?: return true
        if (!isItemReadyInHotbar(slot, inventory)) { moveItemToHotbar(slot, inventory); return false }
        if (clientInstance.currentScreen != null) { pressKey(10, Key.Type.KEY_ESCAPE); return false }
        if (inventory.heldSlot != slot) { selectHotbarSlot(slot); return false }
        return true
    }

    override fun onTick(tick: Tick) {
        val player = clientInstance.fakePlayer
        val inventory = player.inventory
        val config = clientInstance.configuration
        val enemy = perception.snapshot().enemies.firstOrNull { it.entity.entityId == targetId }
        val motion = enemy?.let(::sampleMotion)
        if (returning) {
            if (restoreMelee()) finish()
            return
        }
        if (castTick >= 0) {
            unpressButton(MouseButton.Type.RIGHT_CLICK)
            val elapsed = clientInstance.currentTick - castTick
            if (enemy == null || (elapsed >= 2 && enemy.distance3D <= config.rodCancelRange) ||
                clientInstance.currentTick >= returnTick) {
                returning = true
                if (restoreMelee()) finish()
            }
            return
        }
        if (enemy == null || !enemy.lineOfSightLikelyClear ||
            enemy.distance3D <= config.rodMinRange || enemy.distance3D > config.rodMaxRange) {
            returning = true
            if (restoreMelee()) finish()
            return
        }
        val slot = (0..35).firstOrNull { inventory.getItemStackAt(it)?.item?.id == Item.FISHING_ROD } ?: -1
        tick.finishIf("No rod", slot == -1)
        tick.prerequisite("Rod in hotbar", isItemReadyInHotbar(slot, inventory)) { moveItemToHotbar(slot, inventory) }
        tick.prerequisite("Inventory closed", clientInstance.currentScreen == null) { pressKey(10, Key.Type.KEY_ESCAPE) }
        tick.prerequisite("Rod selected", inventory.heldSlot == resolveHotbarSlot(slot)) { selectHotbarSlot(resolveHotbarSlot(slot)) }
        tick.execute {
            if (inventory.heldItemStack?.item?.id != Item.FISHING_ROD) return@execute
            val yawRadians = Math.toRadians(player.yaw.toDouble())
            val launchDelay = (1.0 + clientInstance.latency.coerceAtLeast(0) / 50.0).coerceAtMost(3.0)
            val self = perception.snapshot().self
            val targetVelocity = motion ?: return@execute
            val solution = RodAim.solveFromFeet(
                enemy.x - player.x - self.velocityX * launchDelay + cos(yawRadians) * 0.16,
                player.y, enemy.y,
                enemy.z - player.z - self.velocityZ * launchDelay + sin(yawRadians) * 0.16,
                targetVelocity.x, targetVelocity.z,
                launchDelay,
                (config.rodPredictionMultiplier / 2.9).coerceIn(0.95, 1.0)
            )
            if (solution == null) {
                returning = true
                if (restoreMelee()) finish()
                return@execute
            }
            castYaw = solution.yaw
            castPitch = (solution.pitch + config.rodPitchBias).coerceIn(-60f, 60f)
            flightTicks = solution.flightTicks
            // Recompute before checking alignment: the previous aim may point at a stale position.
            if (aimTick >= 0 && clientInstance.currentTick > aimTick &&
                abs(angleDifference(player.yaw, castYaw)) <= 3f && abs(player.pitch - castPitch) <= 2f) {
                setMouseYaw(castYaw)
                setMousePitch(castPitch)
                pressButton(5, MouseButton.Type.RIGHT_CLICK)
                castTick = clientInstance.currentTick
                lastRodTick = castTick
                returnTick = castTick + ceil(flightTicks).toInt() + 1
                return@execute
            }
            aimTick = clientInstance.currentTick
            setMouseYaw(castYaw)
            setMousePitch(castPitch)
        }
    }

    // Continuous melee runs after this goal in the same tick. Do not let it overwrite
    // the cast rotation before the pending click and outgoing rotation are processed.
    override fun blocksContinuousAim() = castTick < 0 || clientInstance.currentTick <= castTick + 1
    override fun blocksContinuousAttack() = true
    override fun onEnd() {
        clientInstance.mouse.clearPendingClicks()
        clientInstance.fakePlayer.stopUsingItem()
        // An aborted setup is not a cast; allow a short retry instead of a full rod cooldown.
        if (castTick < 0) retryAfterTick = clientInstance.currentTick + 2
    }
    override fun onEvent(event: Event) = false
    override fun onGameLoop() {}
}
