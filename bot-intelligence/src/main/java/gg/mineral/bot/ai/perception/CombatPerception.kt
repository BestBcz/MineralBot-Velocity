package gg.mineral.bot.ai.perception

import gg.mineral.bot.api.entity.ClientEntity
import gg.mineral.bot.api.entity.living.player.ClientPlayer
import gg.mineral.bot.api.entity.living.player.FakePlayer
import gg.mineral.bot.api.entity.throwable.ClientPotion
import gg.mineral.bot.api.entity.throwable.ClientThrowableEntity
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.math.BoundingBox
import gg.mineral.bot.api.util.MathUtil
import gg.mineral.bot.api.world.ClientWorld
import gg.mineral.bot.api.world.block.Block
import java.util.UUID

class CombatPerception(private val clientInstance: ClientInstance) : MathUtil {
    private var cachedTick = Int.MIN_VALUE
    private var cachedSnapshot: Snapshot? = null

    fun snapshot(): Snapshot {
        val tick = clientInstance.currentTick
        val snapshot = cachedSnapshot
        if (snapshot != null && cachedTick == tick) {
            return snapshot
        }

        val nextSnapshot = buildSnapshot(tick)
        cachedTick = tick
        cachedSnapshot = nextSnapshot
        return nextSnapshot
    }

    fun bestTarget(currentTarget: ClientPlayer? = null, maxRange: Double = clientInstance.configuration.targetSearchRange.toDouble()): PlayerState? {
        return snapshot().bestTargetState(currentTarget, maxRange)
    }

    fun nearestEnemy(maxRange: Double = Double.MAX_VALUE): PlayerState? {
        return snapshot().nearestEnemy?.takeIf { it.distance3D <= maxRange }
    }

    fun nearestVisibleEnemy(maxRange: Double = clientInstance.configuration.targetSearchRange.toDouble()): PlayerState? {
        return snapshot().enemies.firstOrNull { it.distance3D <= maxRange && it.lineOfSightLikelyClear }
    }

    fun canSeeEnemy(maxRange: Double = clientInstance.configuration.targetSearchRange.toDouble()): Boolean {
        return nearestVisibleEnemy(maxRange) != null
    }

    fun distanceToNearestEnemy(horizontal: Boolean = false): Double {
        val enemy = snapshot().nearestEnemy ?: return Double.MAX_VALUE
        return if (horizontal) enemy.distance2D else enemy.distance3D
    }

    fun yawTowards(state: PlayerState): Float {
        val self = snapshot().self
        val dx = state.x - self.x
        val dz = state.z - self.z
        if (abs(dx) < 0.001 && abs(dz) < 0.001) return clientInstance.fakePlayer.yaw
        return wrapYaw(toDegrees(fastArcTan2(-dx, dz)).toFloat())
    }

    fun yawTowardsNearestEnemy(maxRange: Double = clientInstance.configuration.targetSearchRange.toDouble()): Float {
        return nearestEnemy(maxRange)?.let { yawTowards(it) } ?: clientInstance.fakePlayer.yaw
    }

    fun yawAwayFrom(state: PlayerState): Float {
        return wrapYaw(yawTowards(state) + 180.0f)
    }

    fun yawAwayFromNearestEnemy(maxRange: Double = clientInstance.configuration.targetSearchRange.toDouble()): Float {
        return nearestEnemy(maxRange)?.let { yawAwayFrom(it) } ?: clientInstance.fakePlayer.yaw
    }

    fun safeYawAwayFromNearestEnemy(maxRange: Double = clientInstance.configuration.targetSearchRange.toDouble()): Float {
        val enemy = nearestEnemy(maxRange) ?: return clientInstance.fakePlayer.yaw
        val baseYaw = yawAwayFrom(enemy)
        val candidates = listOf(baseYaw, baseYaw - 35f, baseYaw + 35f, baseYaw - 70f, baseYaw + 70f)

        return candidates
            .map { it to terrainAhead(it, 0.9) }
            .filter { (_, terrain) -> !terrain.frontBlocked && !terrain.lavaAhead && !terrain.dropAhead }
            .maxByOrNull { (yaw, terrain) ->
                terrain.traversalScore.toDouble() - abs(angleDifference(baseYaw, yaw).toDouble()) / 90.0
            }
            ?.first
            ?.let(::wrapYaw)
            ?: baseYaw
    }

    fun predictedPlayer(state: PlayerState, ticksAhead: Double, yOffset: Double = 0.0): ClientPlayer {
        val target = state.entity
        val predictedX = state.x + state.velocityX * ticksAhead
        val predictedY = state.y + state.velocityY * ticksAhead + yOffset
        val predictedZ = state.z + state.velocityZ * ticksAhead

        return object : ClientPlayer by target {
            override val x: Double get() = predictedX
            override val y: Double get() = predictedY
            override val z: Double get() = predictedZ
        }
    }

    fun potionProjectiles(durability: Int? = null): List<ProjectileState> {
        return snapshot().projectiles.filter {
            it.kind == ProjectileKind.POTION && (durability == null || it.potionDurability == durability)
        }
    }

    fun terrainAhead(yaw: Float, distance: Double = 0.75): TerrainProbe {
        val snapshot = snapshot()
        val dir = vectorForRotation(0f, yaw)
        val x = snapshot.self.x + dir[0] * distance
        val z = snapshot.self.z + dir[2] * distance
        return terrainAt(x, z)
    }

    fun terrainAt(x: Double, z: Double): TerrainProbe {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world
        val baseY = fakePlayer.boundingBox.minY
        val playerWidth = max(fakePlayer.boundingBox.maxX - fakePlayer.boundingBox.minX, 0.6)
        val playerHeight = max(fakePlayer.boundingBox.maxY - fakePlayer.boundingBox.minY, 1.8)
        val halfWidth = playerWidth * 0.5
        val feetBlock = world.getBlockAt(x, fakePlayer.y, z).id
        val headBlock = world.getBlockAt(x, fakePlayer.y + 1.0, z).id
        val groundBlock = world.getBlockAt(x, fakePlayer.y - 1.0, z).id
        val walkableTopY = findWalkableTopY(world, x, z, baseY, halfWidth)
        val stepDelta = walkableTopY?.let { it - baseY } ?: Double.NEGATIVE_INFINITY
        val headClear = walkableTopY?.let { isPlayerSpaceClear(world, x, it, z, halfWidth, playerHeight) } ?: false
        val bodyBlockedAtCurrentY = hasCollision(
            world,
            playerBoxAt(x, baseY, z, halfWidth, playerHeight)
        )
        val lavaAhead = isLava(feetBlock) || isLava(headBlock)
        val liquidAhead = isLiquid(feetBlock) || isLiquid(headBlock)
        val dangerousDrop = walkableTopY == null || stepDelta < -MAX_SAFE_DROP_HEIGHT || liquidAhead
        val tooTallToClimb = walkableTopY == null || stepDelta > MAX_JUMP_HEIGHT
        val frontBlocked = !lavaAhead && !liquidAhead && (
            (walkableTopY != null && !headClear) ||
                (bodyBlockedAtCurrentY && tooTallToClimb)
            )
        val requiresJump = !frontBlocked &&
            !dangerousDrop &&
            stepDelta > MAX_STEP_HEIGHT + EPSILON &&
            stepDelta <= MAX_JUMP_HEIGHT

        return TerrainProbe(
            x = x,
            z = z,
            feetBlockId = feetBlock,
            headBlockId = headBlock,
            groundBlockId = groundBlock,
            lavaAhead = lavaAhead,
            liquidAhead = liquidAhead,
            frontBlocked = frontBlocked,
            dropAhead = dangerousDrop,
            lowProfileStep = walkableTopY != null && stepDelta > EPSILON && stepDelta <= MAX_STEP_HEIGHT,
            walkableTopY = walkableTopY,
            stepDelta = stepDelta,
            headClear = headClear,
            passable = !lavaAhead && !liquidAhead && !frontBlocked && !dangerousDrop,
            requiresJump = requiresJump,
            dangerousDrop = dangerousDrop
        )
    }

    fun edgeProbe(baseYaw: Float = clientInstance.fakePlayer.yaw, checkDistance: Double = 0.85, depth: Int = 5): EdgeProbe {
        val directions = arrayOf(
            baseYaw,
            baseYaw + 90f,
            baseYaw - 90f
        )

        var nearestDirectionIndex = -1
        var nearestDirectionYaw = baseYaw

        for ((index, yaw) in directions.withIndex()) {
            if (isDangerousEdgeAtYaw(yaw, checkDistance, depth)) {
                nearestDirectionIndex = index
                nearestDirectionYaw = yaw
                break
            }
        }

        if (nearestDirectionIndex < 0) {
            return EdgeProbe(false, baseYaw, -1)
        }

        val safeYaw = when (nearestDirectionIndex) {
            0 -> baseYaw + 180f
            1 -> baseYaw - 90f
            2 -> baseYaw + 90f
            else -> baseYaw
        }
        return EdgeProbe(true, safeYaw, nearestDirectionIndex, nearestDirectionYaw)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isDangerousEdgeAtYaw(yaw: Float, distance: Double, depth: Int): Boolean {
        val fakePlayer = clientInstance.fakePlayer
        val forward = vectorForRotation(0f, yaw)
        val side = vectorForRotation(0f, yaw + 90f)
        val sideOffset = min(0.28, max(fakePlayer.boundingBox.maxX - fakePlayer.boundingBox.minX, 0.6) * 0.45)
        val offsets = doubleArrayOf(0.0, -sideOffset, sideOffset)

        for (offset in offsets) {
            val x = fakePlayer.x + forward[0] * distance + side[0] * offset
            val z = fakePlayer.z + forward[2] * distance + side[2] * offset
            val terrain = terrainAt(x, z)
            if (terrain.liquidAhead || terrain.lavaAhead || terrain.dangerousDrop) {
                return true
            }
        }

        return false
    }

    private fun findWalkableTopY(
        world: ClientWorld,
        x: Double,
        z: Double,
        baseY: Double,
        halfWidth: Double
    ): Double? {
        val horizontalMargin = 0.03
        val searchBox = ProbeBox(
            x - halfWidth + horizontalMargin,
            baseY - EDGE_SEARCH_DEPTH,
            z - halfWidth + horizontalMargin,
            x + halfWidth - horizontalMargin,
            baseY + MAX_JUMP_HEIGHT,
            z + halfWidth - horizontalMargin
        )

        return collisionBoxes(world, searchBox)
            .asSequence()
            .filter { horizontalIntersects(searchBox, it) }
            .filter { it.maxY <= baseY + MAX_JUMP_HEIGHT + EPSILON }
            .filter { it.maxY >= baseY - EDGE_SEARCH_DEPTH - EPSILON }
            .map { it.maxY }
            .maxOrNull()
    }

    private fun isPlayerSpaceClear(
        world: ClientWorld,
        x: Double,
        y: Double,
        z: Double,
        halfWidth: Double,
        height: Double
    ): Boolean {
        return !hasCollision(world, playerBoxAt(x, y, z, halfWidth, height))
    }

    private fun playerBoxAt(x: Double, y: Double, z: Double, halfWidth: Double, height: Double): ProbeBox {
        val margin = 0.01
        return ProbeBox(
            x - halfWidth + margin,
            y + EPSILON,
            z - halfWidth + margin,
            x + halfWidth - margin,
            y + height,
            z + halfWidth - margin
        )
    }

    private fun hasCollision(world: ClientWorld, box: ProbeBox): Boolean {
        return collisionBoxes(world, box).any { intersects(box, it) }
    }

    private fun collisionBoxes(world: ClientWorld, box: ProbeBox): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        val minX = floor(box.minX)
        val maxX = floor(box.maxX + 1.0)
        val minY = floor(box.minY)
        val maxY = floor(box.maxY + 1.0)
        val minZ = floor(box.minZ)
        val maxZ = floor(box.maxZ + 1.0)

        for (blockX in minX until maxX) {
            for (blockY in minY until maxY) {
                for (blockZ in minZ until maxZ) {
                    val block = world.getBlockAt(blockX, blockY, blockZ)
                    val rawCollisionBox = block.getCollisionBoundingBox(world, blockX, blockY, blockZ) ?: continue
                    val collisionBox = normalizedCollisionBox(block, rawCollisionBox)
                    if (intersects(box, collisionBox)) {
                        boxes.add(collisionBox)
                    }
                }
            }
        }

        return boxes
    }

    private fun normalizedCollisionBox(block: Block, collisionBox: BoundingBox): BoundingBox {
        if (!block.javaClass.simpleName.contains("Stairs", ignoreCase = true)) {
            return collisionBox
        }

        val maxStepY = collisionBox.minY + MAX_STEP_HEIGHT
        if (collisionBox.maxY <= maxStepY) {
            return collisionBox
        }

        return SimpleBoundingBox(
            minX = collisionBox.minX,
            minY = collisionBox.minY,
            minZ = collisionBox.minZ,
            maxX = collisionBox.maxX,
            maxY = maxStepY,
            maxZ = collisionBox.maxZ
        )
    }

    private fun intersects(box: ProbeBox, collisionBox: BoundingBox): Boolean {
        return horizontalIntersects(box, collisionBox) &&
            box.maxY > collisionBox.minY &&
            box.minY < collisionBox.maxY
    }

    private fun horizontalIntersects(box: ProbeBox, collisionBox: BoundingBox): Boolean {
        return box.maxX > collisionBox.minX &&
            box.minX < collisionBox.maxX &&
            box.maxZ > collisionBox.minZ &&
            box.minZ < collisionBox.maxZ
    }

    private fun buildSnapshot(tick: Int): Snapshot {
        val fakePlayer = clientInstance.fakePlayer
        val world = fakePlayer.world
        val self = buildPlayerState(fakePlayer, fakePlayer, tick, true)
        val enemies = mutableListOf<PlayerState>()
        val projectiles = mutableListOf<ProjectileState>()

        for (entity in world.entities) {
            if (entity is ClientPlayer &&
                entity.uuid != fakePlayer.uuid &&
                !clientInstance.configuration.friendlyUUIDs.contains(entity.uuid)
            ) {
                enemies.add(buildPlayerState(entity, fakePlayer, tick, false))
                continue
            }

            buildProjectileState(entity, fakePlayer, tick)?.let(projectiles::add)
        }

        val guidedTargetUuid = clientInstance.guidedTargetUuid
        val sortedEnemies = enemies.sortedWith(
            compareBy<PlayerState> { if (it.uuid == guidedTargetUuid) 0 else 1 }
                .thenBy { it.targetScore }
                .thenBy { it.distance3D }
        )
        val sortedProjectiles = projectiles.sortedBy { it.threatScore }

        return Snapshot(
            tick = tick,
            self = self,
            enemies = sortedEnemies,
            projectiles = sortedProjectiles,
            guidedTargetUuid = guidedTargetUuid
        )
    }

    private fun buildPlayerState(player: ClientPlayer, self: FakePlayer, tick: Int, selfState: Boolean): PlayerState {
        val velocityX = measuredMotion(player.motionX, player.x, player.lastX)
        val velocityY = measuredMotion(player.motionY, player.y, player.lastY)
        val velocityZ = measuredMotion(player.motionZ, player.z, player.lastZ)
        val distance2D = if (selfState) 0.0 else self.distance2DTo(player.x, player.z)
        val distance3D = if (selfState) 0.0 else self.distance3DTo(player)
        val heldItem = player.inventory.heldItemStack
        val aimErrorToSelf = if (selfState) 0f else aimErrorToSelf(player, self)
        val horizontalSpeed = sqrt(velocityX * velocityX + velocityZ * velocityZ)
        val movingTowardSelf = !selfState && isMotionToward(
            fromX = player.x,
            fromZ = player.z,
            velocityX = velocityX,
            velocityZ = velocityZ,
            toX = self.x,
            toZ = self.z
        )
        val lineOfSight = selfState || hasLikelyLineOfSight(self, player)
        val targetScore =
            if (selfState) 0.0
            else distance3D +
                (if (lineOfSight) -0.7 else 4.0) +
                (if (aimErrorToSelf <= 35f) -0.35 else 0.0) +
                (if (movingTowardSelf) -0.25 else 0.0) +
                (if (player.health <= 6f) -0.25 else 0.0) +
                -(heldItem?.attackDamage ?: 0.0) * 0.02

        return PlayerState(
            entity = player,
            uuid = player.uuid,
            username = player.username,
            tick = tick,
            x = player.x,
            y = player.y,
            z = player.z,
            yaw = player.yaw,
            pitch = player.pitch,
            velocityX = velocityX,
            velocityY = velocityY,
            velocityZ = velocityZ,
            horizontalSpeed = horizontalSpeed,
            health = player.health,
            hunger = player.hunger,
            eatingOrDrinking = player.isEatingOrDrinking,
            onGround = player.isOnGround,
            sprinting = player.isSprinting,
            activePotionEffectIds = player.activePotionEffectIds.toSet(),
            heldItemId = heldItem?.item?.id,
            heldAttackDamage = heldItem?.attackDamage ?: 0.0,
            distance2D = distance2D,
            distance3D = distance3D,
            aimErrorToSelf = aimErrorToSelf,
            lookingAtSelf = aimErrorToSelf <= 35f,
            movingTowardSelf = movingTowardSelf,
            lineOfSightLikelyClear = lineOfSight,
            targetScore = targetScore
        )
    }

    private fun buildProjectileState(entity: ClientEntity, self: FakePlayer, tick: Int): ProjectileState? {
        val kind = projectileKind(entity) ?: return null
        val relX = entity.x - self.x
        val relY = entity.y - (self.y + self.eyeHeight)
        val relZ = entity.z - self.z
        val speedSq = entity.motionX * entity.motionX + entity.motionY * entity.motionY + entity.motionZ * entity.motionZ
        val dot = relX * entity.motionX + relY * entity.motionY + relZ * entity.motionZ
        val timeToClosest =
            if (speedSq > 0.000001) (-dot / speedSq).coerceIn(0.0, 20.0)
            else 20.0
        val closestX = relX + entity.motionX * timeToClosest
        val closestY = relY + entity.motionY * timeToClosest
        val closestZ = relZ + entity.motionZ * timeToClosest
        val closestDistance = sqrt(closestX * closestX + closestY * closestY + closestZ * closestZ)
        val movingTowardSelf = dot < -0.01
        val distance3D = self.distance3DTo(entity)
        val incoming = movingTowardSelf && timeToClosest <= 12.0 && closestDistance <= 2.4
        val threatScore =
            if (incoming) timeToClosest + closestDistance * 3.0
            else 1000.0 + distance3D

        return ProjectileState(
            entity = entity,
            kind = kind,
            tick = tick,
            x = entity.x,
            y = entity.y,
            z = entity.z,
            velocityX = entity.motionX,
            velocityY = entity.motionY,
            velocityZ = entity.motionZ,
            distance3D = distance3D,
            movingTowardSelf = movingTowardSelf,
            timeToClosestTicks = timeToClosest,
            closestDistanceToSelf = closestDistance,
            incomingThreat = incoming,
            threatScore = threatScore,
            potionDurability = (entity as? ClientPotion)?.potionDurability
        )
    }

    private fun projectileKind(entity: ClientEntity): ProjectileKind? {
        if (entity is ClientPotion) return ProjectileKind.POTION
        if (entity is ClientThrowableEntity) return ProjectileKind.THROWABLE

        val simpleName = entity.javaClass.simpleName
        return when {
            simpleName.contains("Arrow", ignoreCase = true) -> ProjectileKind.ARROW
            simpleName.contains("FishHook", ignoreCase = true) -> ProjectileKind.FISHING_HOOK
            simpleName.contains("Fireball", ignoreCase = true) -> ProjectileKind.FIREBALL
            else -> null
        }
    }

    private fun measuredMotion(rawMotion: Double, current: Double, previous: Double): Double {
        return if (abs(rawMotion) > 0.001) rawMotion else current - previous
    }

    private fun isMotionToward(
        fromX: Double,
        fromZ: Double,
        velocityX: Double,
        velocityZ: Double,
        toX: Double,
        toZ: Double
    ): Boolean {
        val dx = toX - fromX
        val dz = toZ - fromZ
        return dx * velocityX + dz * velocityZ > 0.025
    }

    private fun aimErrorToSelf(player: ClientPlayer, self: FakePlayer): Float {
        val dx = self.x - player.x
        val dz = self.z - player.z
        if (abs(dx) < 0.001 && abs(dz) < 0.001) return 0f
        val yawToSelf = toDegrees(fastArcTan2(-dx, dz)).toFloat()
        return abs(angleDifference(player.yaw, yawToSelf).toDouble()).toFloat()
    }

    private fun hasLikelyLineOfSight(self: FakePlayer, target: ClientPlayer): Boolean {
        val world = self.world
        val startX = self.x
        val startY = self.y + self.eyeHeight
        val startZ = self.z
        val endX = target.x
        val endY = target.y + target.eyeHeight * 0.75
        val endZ = target.z

        val distance = self.distance3DTo(target).coerceAtLeast(0.001)
        val steps = (distance * 2.0).toInt().coerceIn(2, 18)

        for (step in 1 until steps) {
            val t = step.toDouble() / steps.toDouble()
            val blockId = world.getBlockAt(
                startX + (endX - startX) * t,
                startY + (endY - startY) * t,
                startZ + (endZ - startZ) * t
            ).id
            if (isOccludingVision(blockId)) {
                return false
            }
        }
        return true
    }

    private fun isOccludingVision(blockId: Int): Boolean {
        return isBlocking(blockId) &&
            blockId != Block.SNOW_LAYER &&
            blockId != Block.CARPET &&
            blockId != Block.TALL_GRASS &&
            blockId != Block.TORCH &&
            blockId != Block.FIRE
    }

    private fun isBlocking(blockId: Int): Boolean {
        return blockId != Block.AIR &&
            blockId != Block.WATER_FLOWING &&
            blockId != Block.WATER_STILL &&
            blockId != Block.LAVA_FLOWING &&
            blockId != Block.LAVA_STILL
    }

    private fun isLiquid(blockId: Int): Boolean {
        return blockId == Block.WATER_FLOWING ||
            blockId == Block.WATER_STILL ||
            blockId == Block.LAVA_FLOWING ||
            blockId == Block.LAVA_STILL
    }

    private fun isLava(blockId: Int): Boolean {
        return blockId == Block.LAVA_FLOWING || blockId == Block.LAVA_STILL
    }

    private fun wrapYaw(yaw: Float): Float {
        var wrapped = yaw % 360.0f
        if (wrapped >= 180.0f) wrapped -= 360.0f
        if (wrapped < -180.0f) wrapped += 360.0f
        return wrapped
    }

    data class Snapshot(
        val tick: Int,
        val self: PlayerState,
        val enemies: List<PlayerState>,
        val projectiles: List<ProjectileState>,
        val guidedTargetUuid: UUID?
    ) {
        val nearestEnemy: PlayerState?
            get() = enemies.minByOrNull { it.distance3D }

        val nearestIncomingThreat: ProjectileState?
            get() = projectiles.firstOrNull { it.incomingThreat }

        fun stateFor(player: ClientPlayer?): PlayerState? {
            if (player == null) return null
            if (player.uuid == self.uuid) return self
            return enemies.firstOrNull { it.uuid == player.uuid }
        }

        fun bestTargetState(currentTarget: ClientPlayer?, range: Double): PlayerState? {
            val guided = guidedTargetUuid?.let { uuid -> enemies.firstOrNull { it.uuid == uuid } }
            if (guided != null && guided.distance3D <= range) {
                return guided
            }

            val current = stateFor(currentTarget)
            if (current != null && current.distance3D <= range) {
                return current
            }

            return enemies.firstOrNull { it.distance3D <= range }
        }
    }

    data class PlayerState(
        val entity: ClientPlayer,
        val uuid: UUID,
        val username: String,
        val tick: Int,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val velocityX: Double,
        val velocityY: Double,
        val velocityZ: Double,
        val horizontalSpeed: Double,
        val health: Float,
        val hunger: Float,
        val eatingOrDrinking: Boolean,
        val onGround: Boolean,
        val sprinting: Boolean,
        val activePotionEffectIds: Set<Int>,
        val heldItemId: Int?,
        val heldAttackDamage: Double,
        val distance2D: Double,
        val distance3D: Double,
        val aimErrorToSelf: Float,
        val lookingAtSelf: Boolean,
        val movingTowardSelf: Boolean,
        val lineOfSightLikelyClear: Boolean,
        val targetScore: Double
    ) {
        val holdingWeapon: Boolean
            get() = heldItemId?.let { Item.Type.SWORD.isType(it) || it == Item.IRON_AXE || it == Item.DIAMOND_AXE } == true

        val pressuringSelf: Boolean
            get() = distance3D <= 5.0 && (movingTowardSelf || lookingAtSelf || holdingWeapon)
    }

    data class ProjectileState(
        val entity: ClientEntity,
        val kind: ProjectileKind,
        val tick: Int,
        val x: Double,
        val y: Double,
        val z: Double,
        val velocityX: Double,
        val velocityY: Double,
        val velocityZ: Double,
        val distance3D: Double,
        val movingTowardSelf: Boolean,
        val timeToClosestTicks: Double,
        val closestDistanceToSelf: Double,
        val incomingThreat: Boolean,
        val threatScore: Double,
        val potionDurability: Int?
    )

    data class TerrainProbe(
        val x: Double,
        val z: Double,
        val feetBlockId: Int,
        val headBlockId: Int,
        val groundBlockId: Int,
        val lavaAhead: Boolean,
        val liquidAhead: Boolean,
        val frontBlocked: Boolean,
        val dropAhead: Boolean,
        val lowProfileStep: Boolean,
        val walkableTopY: Double?,
        val stepDelta: Double,
        val headClear: Boolean,
        val passable: Boolean,
        val requiresJump: Boolean,
        val dangerousDrop: Boolean
    ) {
        val traversalScore: Int
            get() {
                var score = 0
                if (passable) score += 3
                if (headClear) score += 1
                if (lowProfileStep) score += 1
                if (lavaAhead) score -= 5
                if (liquidAhead) score -= 4
                if (dangerousDrop) score -= 4
                if (frontBlocked) score -= 3
                return score
            }
    }

    data class EdgeProbe(
        val nearEdge: Boolean,
        val safeYaw: Float,
        val edgeDirectionIndex: Int,
        val edgeYaw: Float = safeYaw
    )

    enum class ProjectileKind {
        POTION,
        THROWABLE,
        ARROW,
        FISHING_HOOK,
        FIREBALL
    }

    private data class ProbeBox(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double
    )

    private data class SimpleBoundingBox(
        override var minX: Double,
        override var minY: Double,
        override var minZ: Double,
        override var maxX: Double,
        override var maxY: Double,
        override var maxZ: Double
    ) : BoundingBox

    companion object {
        private const val EPSILON = 1.0E-4
        private const val MAX_STEP_HEIGHT = 0.6
        private const val MAX_JUMP_HEIGHT = 1.25
        private const val MAX_SAFE_DROP_HEIGHT = 1.25
        private const val EDGE_SEARCH_DEPTH = 5.0
    }
}
