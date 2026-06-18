package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item

/**
 * Fishing Rod Combat Goal for creating distance advantages.
 *
 * Uses fishing rod to:
 * - Pull enemies towards you for combos
 * - Knock enemies back when they're attacking
 * - Create gap to escape or pot
 */
class FishingRodGoal(clientInstance: ClientInstance) :
        InventoryGoal(clientInstance), Sporadic, Timebound {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 12

    private val perception = CombatPerception(clientInstance)
    private var lastRodTick = 0
    private var rodState = RodState.IDLE

    private enum class RodState {
        IDLE,
        THROWING,
        IN_FLIGHT
    }

    override fun shouldExecute(): Boolean {
        val config = clientInstance.configuration
        if (clientInstance.currentTick - lastRodTick < config.rodCooldownTicks) return false

        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        if (!inventory.contains(Item.FISHING_ROD)) return false

        val enemy = getClosestEnemyState() ?: return false
        val distance = enemy.distance3D

        return distance >= config.rodMinRange && distance <= config.rodMaxRange
    }

    override fun onStart() {
        rodState = RodState.IDLE
    }

    private fun getClosestEnemyState(): CombatPerception.PlayerState? {
        val targetSearchRange = clientInstance.configuration.targetSearchRange
        return perception.snapshot().bestTargetState(null, targetSearchRange.toDouble())
    }

    private fun getRodSlot(): Int {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            if (itemStack.item.id == Item.FISHING_ROD) {
                return i
            }
        }
        return -1
    }

    private fun getBestMeleeWeaponSlot(): Int {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory

        var bestSlot = 0
        var bestDamage = Double.NEGATIVE_INFINITY

        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            val damage = itemStack.attackDamage
            if (damage > bestDamage) {
                bestDamage = damage
                bestSlot = i
            }
        }

        return bestSlot
    }

    private fun switchBackToMelee(inventory: gg.mineral.bot.api.inv.Inventory) {
        val meleeWeaponSlot = getBestMeleeWeaponSlot()
        if (meleeWeaponSlot <= 8) {
            selectHotbarSlot(resolveHotbarSlot(meleeWeaponSlot))
            return
        }

        moveItemToHotbar(meleeWeaponSlot, inventory)
        selectHotbarSlot(resolveHotbarSlot(meleeWeaponSlot))
    }

    override fun onTick(tick: Tick) {
        val rodSlot = getRodSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        val enemyState = getClosestEnemyState()
        val enemy = enemyState?.entity

        tick.finishIf("No Rod Found", rodSlot == -1)
        tick.finishIf("No Enemy", enemy == null)

        if (enemy == null || enemyState == null) return

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        // Enemy in hit range -> immediately hand control back to melee goal.
        val distance = enemyState.distance3D
        if (distance <= clientInstance.configuration.rodCancelRange) {
            tick.execute {
                switchBackToMelee(inventory)
                lastRodTick = clientInstance.currentTick
                finish()
            }
            return
        }

        tick.prerequisite("In Hotbar", rodSlot <= 8) { moveItemToHotbar(rodSlot, inventory) }

        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(rodSlot)) {
            selectHotbarSlot(resolveHotbarSlot(rodSlot))
        }

        tick.finishIf("Not Holding Rod", inventory.heldItemStack?.item?.id != Item.FISHING_ROD)

        when (rodState) {
            RodState.IDLE -> {
                val config = clientInstance.configuration
                // Aim at enemy with prediction
                val predictedX = enemyState.x + enemyState.velocityX * config.rodPredictionMultiplier
                val predictedZ = enemyState.z + enemyState.velocityZ * config.rodPredictionMultiplier

                val dx = predictedX - fakePlayer.x
                val dz = predictedZ - fakePlayer.z
                val targetY =
                        if (enemyState.onGround)
                                enemyState.y - 0.83
                        else enemyState.y + enemy.eyeHeight * 0.62
                val dy = targetY - (fakePlayer.y + fakePlayer.eyeHeight)

                val horizDist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001)
                val yaw =
                        Math.toDegrees(-fastArcTan(dx / dz)).toFloat().let {
                            when {
                                dz < 0 && dx < 0 ->
                                        (90 + Math.toDegrees(fastArcTan(dz / dx))).toFloat()
                                dz < 0 && dx > 0 ->
                                        (-90 + Math.toDegrees(fastArcTan(dz / dx))).toFloat()
                                else -> it
                            }
                        }

                // Keep rod aim lower for grounded targets, but a bit higher if enemy is airborne.
                val basePitch = Math.toDegrees(-fastArcTan(dy / horizDist)).toFloat()
                val downwardBias =
                        ((horizDist / 8.0).coerceIn(5.0, 15.0) - if (enemyState.onGround) 0.0 else 2.0)
                                .toFloat()
                val pitch = (basePitch + downwardBias + config.rodPitchBias).coerceIn(-20f, 36f)

                setMouseYaw(yaw)
                setMousePitch(pitch)

                tick.execute { rodState = RodState.THROWING }
            }

            RodState.THROWING -> {
                tick.execute {
                    pressButton(25, MouseButton.Type.RIGHT_CLICK)
                    rodState = RodState.IN_FLIGHT
                }
            }

            RodState.IN_FLIGHT -> {
                // No explicit reel-in needed: switch back to melee weapon,
                // hook will be naturally cleaned up by item switch / later rod use.
                tick.execute {
                    if (tickCount > 4) {
                        switchBackToMelee(inventory)
                        lastRodTick = clientInstance.currentTick
                        finish()
                    }
                }
            }
        }
    }

    override fun blocksContinuousAim(): Boolean = true

    override fun blocksContinuousAttack(): Boolean = true
    override fun onEnd() {
        rodState = RodState.IDLE
    }

    override fun onEvent(event: Event): Boolean {
        return false
    }

    override fun onGameLoop() {}
}
