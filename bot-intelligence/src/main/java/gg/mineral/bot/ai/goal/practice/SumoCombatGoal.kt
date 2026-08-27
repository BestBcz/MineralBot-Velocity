package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.living.player.ClientPlayer
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.event.entity.EntityHurtEvent
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.instance.ClientInstance

/**
 * Advanced Sumo Combat Goal for pushing enemies off platforms.
 * 
 * Key strategies:
 * - W-tap for knockback advantage
 * - Strafe to avoid enemy attacks
 * - Edge detection to avoid falling
 * - Aggressive knockback combos
 */
class SumoCombatGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic {
    override var executing: Boolean = false

    private val perception = CombatPerception(clientInstance)
    private var target: ClientPlayer? = null
    private var lastTargetSwitchTick = 0
    private var lastSprintResetTick = 0
    private var strafeDirection: Byte = 0
    private var strafeLockedUntilTick = -1
    private var forwardSuppressedUntilTick = -1
    private var comboCount = 0

    private val meanDelay = (1000 / clientInstance.configuration.averageCps).toLong()
    private val deviation = kotlin.math.abs((1000 / (clientInstance.configuration.averageCps + 1)).toLong() - meanDelay)
    private var nextClick: Long = 0

    // Edge detection
    private var isNearEdge = false
    private var edgeDirection = 0f

    override fun shouldExecute(): Boolean = true

    override fun onStart() {
        forwardSuppressedUntilTick = -1
        strafeDirection = 0
        strafeLockedUntilTick = -1
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        comboCount = 0
    }

    private fun suppressForwardFor(ticks: Int) {
        val untilTick = clientInstance.currentTick + ticks
        if (untilTick > forwardSuppressedUntilTick) {
            forwardSuppressedUntilTick = untilTick
        }
        unpressKey(Key.Type.KEY_W)
    }

    private fun isForwardSuppressed(): Boolean = clientInstance.currentTick <= forwardSuppressedUntilTick

    private fun maintainForwardMovement() {
        if (isForwardSuppressed()) {
            unpressKey(Key.Type.KEY_W)
            pressKey(Key.Type.KEY_LCONTROL)
            return
        }

        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
    }

    private fun releaseStrafe() {
        strafeDirection = 0
        strafeLockedUntilTick = -1
        unpressKey(Key.Type.KEY_A, Key.Type.KEY_D)
    }

    private fun findTarget() {
        val targetSearchRange = clientInstance.configuration.targetSearchRange
        val snapshot = perception.snapshot()

        if (clientInstance.currentTick - lastTargetSwitchTick < 20) {
            val currentTargetState = snapshot.stateFor(target)
            val guidedTargetUuid = clientInstance.guidedTargetUuid
            if (currentTargetState != null && currentTargetState.distance3D <= targetSearchRange &&
                (guidedTargetUuid == null || currentTargetState.uuid == guidedTargetUuid)
            ) {
                return
            }
        }

        val nextTarget = snapshot.bestTargetState(target, targetSearchRange.toDouble())?.entity

        if (nextTarget !== this.target) {
            lastTargetSwitchTick = clientInstance.currentTick
            this.target = nextTarget
        }
    }

    private fun aimAtTarget() {
        val target = this.target ?: return
        val fakePlayer = clientInstance.fakePlayer
        val targetState = perception.snapshot().stateFor(target)
        val aimTarget =
            if (targetState == null) target
            else object : ClientPlayer by target {
                override val x: Double get() = targetState.x + targetState.velocityX * 0.18
                override val z: Double get() = targetState.z + targetState.velocityZ * 0.18
            }
        val optimalAngles = computeOptimalYawAndPitch(fakePlayer, aimTarget)
        
        val config = clientInstance.configuration
        val yawSpeed = config.horizontalAimSpeed * 3.0f
        val pitchSpeed = config.verticalAimSpeed * 3.0f

        setMouseYaw(getRotationTarget(fakePlayer.yaw, optimalAngles[1], yawSpeed, config.horizontalAimAccuracy))
        setMousePitch(getRotationTarget(fakePlayer.pitch, optimalAngles[0], pitchSpeed, config.verticalAimAccuracy))
    }

    private fun getRotationTarget(current: Float, target: Float, turnSpeed: Float, accuracy: Float): Float {
        val difference = angleDifference(current, target)

        if (abs(difference.toDouble()) > turnSpeed) return current + signum(difference) * turnSpeed
        if (accuracy >= 1) return target

        val deviation = 3f / max(0.01f, accuracy)
        val fakePlayer = clientInstance.fakePlayer
        return fakePlayer.random.nextGaussian(target.toDouble(), deviation.toDouble()).toFloat()
    }
    
    /**
     * Check whether the next movement lane would step into void, liquid, or a large drop.
     */
    private fun checkEdge(): Boolean {
        val edge = perception.edgeProbe()
        isNearEdge = edge.nearEdge
        edgeDirection = edge.safeYaw
        return edge.nearEdge
    }

    /**
     * Sumo-specific strafing - more aggressive, focuses on knockback positioning.
     */
    private fun strafe() {
        val target = this.target ?: return
        val fakePlayer = clientInstance.fakePlayer
        val distance = perception.snapshot().stateFor(target)?.distance3D ?: fakePlayer.distance3DTo(target)

        // Only take over movement when the next lane is actually unsafe.
        if (isNearEdge) {
            releaseStrafe()
            setMouseYaw(edgeDirection)
            maintainForwardMovement()
            return
        }

        if (!fakePlayer.isOnGround || distance > 3.5) {
            releaseStrafe()
            return
        }

        // Circle strafe for positioning advantage
        strafeDirection = lockedStrafeDirection(target)

        when (strafeDirection.toInt()) {
            1 -> {
                unpressKey(Key.Type.KEY_D)
                pressKey(Key.Type.KEY_A)
            }
            2 -> {
                unpressKey(Key.Type.KEY_A)
                pressKey(Key.Type.KEY_D)
            }
        }
    }

    private fun calculateStrafeDirection(target: ClientPlayer): Byte {
        val fakePlayer = clientInstance.fakePlayer
        val targetState = perception.snapshot().stateFor(target)
        val toPlayer = doubleArrayOf(
            fakePlayer.x - target.x,
            fakePlayer.y - target.y,
            fakePlayer.z - target.z
        )
        val aimVector = vectorForRotation(target.pitch, targetState?.yaw ?: target.yaw)
        val crossProduct = (toPlayer[0] * aimVector[2] - toPlayer[2] * aimVector[0]).toFloat()

        return if (crossProduct > 0) 2.toByte() else 1.toByte()
    }

    private fun lockedStrafeDirection(target: ClientPlayer): Byte {
        val proposedDirection = calculateStrafeDirection(target)
        if (strafeDirection == 0.toByte() || clientInstance.currentTick >= strafeLockedUntilTick) {
            strafeDirection = proposedDirection
            strafeLockedUntilTick = clientInstance.currentTick + 5 + clientInstance.fakePlayer.random.nextInt(4)
        }
        return strafeDirection
    }

    /**
     * W-tap for sprint reset - critical for Sumo knockback combos.
     */
    private fun wtap() {
        val target = this.target ?: return
        val fakePlayer = clientInstance.fakePlayer
        val distance = fakePlayer.distance3DTo(target)

        // Only w-tap when in range and on ground
        if (distance > 4.0 || !fakePlayer.isOnGround) return

        val config = clientInstance.configuration

        if (config.sprintResetAccuracy >= 1 || fakePlayer.random.nextFloat() < config.sprintResetAccuracy) {
            suppressForwardFor(2)
        }
    }

    private fun attackTarget() {
        val fakePlayer = clientInstance.fakePlayer
        nextClick = (timeMillis() + fakePlayer.random.nextGaussian(meanDelay.toDouble(), deviation.toDouble())).toLong()
        pressButton(25, MouseButton.Type.LEFT_CLICK)
    }

    override fun onTick(tick: Tick) {
        maintainForwardMovement()

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }

        tick.execute {
            checkEdge()
            findTarget()
            aimAtTarget()
        }
    }

    override fun onEvent(event: Event): Boolean {
        if (event is EntityHurtEvent) return onEntityHurt(event)
        return false
    }

    private fun onEntityHurt(event: EntityHurtEvent): Boolean {
        if (clientInstance.currentTick - lastSprintResetTick < 8) return false

        val entity = event.attackedEntity
        val fakePlayer = clientInstance.fakePlayer
        val target = this.target

        if (target != null && entity.uuid == target.uuid) {
            comboCount++
            wtap()
            lastSprintResetTick = clientInstance.currentTick
        }

        // Reset combo if we got hit
        if (entity.uuid == fakePlayer.uuid) {
            comboCount = 0
        }

        return false
    }

    override fun onEnd() {
    }

    override fun onGameLoop() {
        strafe()
        if (timeMillis() >= nextClick) attackTarget()
    }
}
