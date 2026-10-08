package gg.mineral.bot.api.navigation

import kotlin.math.floor

data class BlockPos(val x: Int, val y: Int, val z: Int) {
    fun offset(dx: Int, dy: Int, dz: Int) = BlockPos(x + dx, y + dy, z + dz)
    companion object {
        fun at(x: Double, y: Double, z: Double) = BlockPos(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
    }
}

data class NavVec(val x: Double, val y: Double, val z: Double) {
    val block get() = BlockPos.at(x, y, z)
}

data class NavBox(val minX: Double, val minY: Double, val minZ: Double,
                  val maxX: Double, val maxY: Double, val maxZ: Double) {
    fun intersects(other: NavBox) = maxX > other.minX + 1e-6 && minX < other.maxX - 1e-6 &&
        maxY > other.minY + 1e-6 && minY < other.maxY - 1e-6 &&
        maxZ > other.minZ + 1e-6 && minZ < other.maxZ - 1e-6
}

data class NavBlock(val id: Int, val metadata: Int = 0, val boxes: List<NavBox> = emptyList(),
                    val water: Boolean = false, val hazardous: Boolean = false,
                    val replaceable: Boolean = false, val flow: NavVec = NavVec(0.0, 0.0, 0.0),
                    val breakTicks: Double = Double.POSITIVE_INFINITY, val toolSlot: Int = -1)

interface NavigationWorld {
    val revision: Long
    fun block(pos: BlockPos): NavBlock? // null means unloaded, never air
    fun canBreak(pos: BlockPos, block: NavBlock): Boolean
    fun canPlace(pos: BlockPos): Boolean
}

enum class BlockActionKind { BREAK, PLACE }
data class BlockAction(val kind: BlockActionKind, val pos: BlockPos,
                       val blockId: Int, val metadata: Int = 0, val slot: Int = -1)
enum class ActionStatus { IDLE, WAITING, EXECUTING, CONFIRMED, FAILED }
data class NavigationState(val position: NavVec, val yaw: Float, val onGround: Boolean,
                           val inWater: Boolean, val air: Int, val width: Double = 0.6,
                           val height: Double = 1.8, val velocity: NavVec = NavVec(0.0, 0.0, 0.0))
data class BuildingMaterial(val id: Int, val metadata: Int, val slot: Int, val count: Int)

/** Minecraft adapters own interaction/confirmation. Search never mutates the live world. */
interface NavigationContext {
    val world: NavigationWorld
    fun state(): NavigationState
    fun material(): BuildingMaterial?
    fun move(keys: Set<gg.mineral.bot.api.controls.Key.Type>) {}
    fun requestAction(action: BlockAction): ActionStatus
    fun actionStatus(): ActionStatus
    fun tickAction()
    fun cancelAction()
    fun reset()
}
