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
    private var lastRodTick = -100
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
        if (clientInstance.currentTick - lastRodTick < config.rodCooldownTicks) return false
        if (!clientInstance.fakePlayer.inventory.contains(Item.FISHING_ROD)) return false
        val enemy = perception.bestTarget() ?: return false
        return enemy.distance3D > maxOf(config.rodMinRange, config.rodCancelRange) &&
            enemy.distance3D <= config.rodMaxRange && enemy.lineOfSightLikelyClear
    }

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
        if (returning) {
            if (restoreMelee()) finish()
            return
        }
        if (castTick >= 0) {
            unpressButton(MouseButton.Type.RIGHT_CLICK)
            if (enemy == null || enemy.distance3D <= config.rodCancelRange || clientInstance.currentTick >= returnTick) {
                returning = true
                if (restoreMelee()) finish()
            }
            return
        }
        if (enemy == null || !enemy.lineOfSightLikelyClear ||
            enemy.distance3D <= config.rodCancelRange || enemy.distance3D > config.rodMaxRange) {
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
            // The mouse rotation is applied after the AI tick. Cast only after it is visible.
            if (aimTick >= 0 && clientInstance.currentTick > aimTick &&
                abs(angleDifference(player.yaw, castYaw)) <= 3f && abs(player.pitch - castPitch) <= 2f) {
                pressButton(5, MouseButton.Type.RIGHT_CLICK)
                castTick = clientInstance.currentTick
                lastRodTick = castTick
                returnTick = castTick + ceil(flightTicks).toInt() + 1
                return@execute
            }
            val yawRadians = Math.toRadians(player.yaw.toDouble())
            val launchDelay = (1.0 + clientInstance.latency.coerceAtLeast(0) / 50.0).coerceAtMost(3.0)
            val self = perception.snapshot().self
            val solution = RodAim.solve(
                enemy.x - player.x - self.velocityX * launchDelay + cos(yawRadians) * 0.16,
                enemy.y + 0.75 - (player.y + player.eyeHeight - 0.1),
                enemy.z - player.z - self.velocityZ * launchDelay + sin(yawRadians) * 0.16,
                enemy.velocityX, if (enemy.onGround) 0.0 else enemy.velocityY, enemy.velocityZ,
                launchDelay,
                config.rodPredictionMultiplier / 2.9
            )
            if (solution == null) {
                returning = true
                if (restoreMelee()) finish()
                return@execute
            }
            castYaw = solution.yaw
            castPitch = (solution.pitch + config.rodPitchBias).coerceIn(-60f, 60f)
            flightTicks = solution.flightTicks
            aimTick = clientInstance.currentTick
            setMouseYaw(castYaw)
            setMousePitch(castPitch)
        }
    }

    override fun blocksContinuousAim() = castTick < 0
    override fun blocksContinuousAttack() = true
    override fun onEnd() {
        clientInstance.mouse.clearPendingClicks()
        clientInstance.fakePlayer.stopUsingItem()
        // Also back off failed/aborted attempts instead of immediately monopolizing combat.
        if (castTick < 0) lastRodTick = clientInstance.currentTick
    }
    override fun onEvent(event: Event) = false
    override fun onGameLoop() {}
}
