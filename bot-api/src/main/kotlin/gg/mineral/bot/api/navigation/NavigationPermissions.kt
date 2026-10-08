package gg.mineral.bot.api.navigation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class NavigationPermissions(val session: UUID, val world: UUID, val revision: Long,
    val building: Boolean, val breakAll: Boolean, val bounds: NavBox, val maxBuildY: Int,
    val breakMaterials: Set<Int>, val breakPositions: Set<BlockPos>) {
    fun contains(p: BlockPos) = p.x >= bounds.minX && p.x <= bounds.maxX &&
        p.y >= bounds.minY && p.y <= bounds.maxY && p.z >= bounds.minZ && p.z <= bounds.maxZ
    fun canBreak(p: BlockPos, id: Int) = building && contains(p) &&
        (breakAll || id in breakMaterials || p in breakPositions)
    fun canPlace(p: BlockPos) = building && contains(p) && p.y <= maxBuildY
}

/** Atomic multipart snapshots and ordered deltas. A gap disables edits until a full snapshot. */
class NavigationPermissionStore {
    var token = ""; private set
    var permissions: NavigationPermissions? = null; private set
    var reply: Pair<Long, Int>? = null; private set
    private var assembly: NavigationPermissions? = null
    private var parts = mutableMapOf<Int, Set<BlockPos>>()
    private var partCount = 0
    private var lastRevision = -1L

    fun bind(token: String) {
        this.token = token
        permissions = null; assembly = null; parts.clear(); reply = null; lastRevision = -1
    }

    fun receive(payload: ByteArray) {
        if (payload.size > 32767 || token.isEmpty()) return
        try {
            val input = DataInputStream(ByteArrayInputStream(payload))
            val kind = input.readUTF()
            val version = input.readInt()
            if (input.readUTF() != token) return
            if (version != VERSION) {
                permissions = null; assembly = null; parts.clear(); reply = null
                return
            }
            val session = input.uuid(); val world = input.uuid(); val revision = input.readLong()
            when (kind) {
                SNAPSHOT -> {
                    val index = input.readInt(); val count = input.readInt()
                    require(count in 1..4096 && index in 0 until count)
                    val building = input.readBoolean(); val all = input.readBoolean()
                    val lower = BlockPos(input.readInt(), input.readInt(), input.readInt())
                    val upper = BlockPos(input.readInt(), input.readInt(), input.readInt())
                    val bounds = NavBox(lower.x.toDouble(), lower.y.toDouble(), lower.z.toDouble(),
                        upper.x.toDouble(), upper.y.toDouble(), upper.z.toDouble())
                    val maxY = input.readInt()
                    val materials = (0 until input.count(256)).map { input.readInt() }.toSet()
                    val positions = input.positions()
                    require(input.available() == 0)
                    if (revision <= lastRevision) return
                    val descriptor = NavigationPermissions(session, world, revision, building, all, bounds, maxY, materials, emptySet())
                    if (assembly?.revision != revision) {
                        if (assembly != null && revision < assembly!!.revision) return
                        assembly = descriptor; partCount = count; parts.clear()
                    }
                    require(assembly == descriptor && count == partCount)
                    parts[index] = positions
                    if (parts.size == count) {
                        permissions = descriptor.copy(breakPositions = parts.values.flatten().toSet())
                        lastRevision = revision; assembly = null; parts.clear()
                    }
                }
                DELTA -> {
                    val previous = input.readLong()
                    val additions = input.positions(); val removals = input.positions()
                    require(input.available() == 0)
                    val current = permissions ?: return
                    if (current.session != session || current.world != world || revision <= lastRevision) return
                    if (previous != current.revision || revision != previous + 1) { permissions = null; return }
                    permissions = current.copy(revision = revision,
                        breakPositions = current.breakPositions + additions - removals)
                    lastRevision = revision
                }
                RESULT -> {
                    val id = input.readLong(); val status = input.readUnsignedByte()
                    require(status in 0..2 && input.available() == 0)
                    val current = permissions ?: return
                    if (current.session == session && current.world == world) reply = id to status
                }
            }
        } catch (_: Exception) {
            // Malformed input cannot grant permissions or partially publish a snapshot.
        }
    }

    fun check(id: Long, action: BlockAction, kind: String = CHECK): ByteArray? {
        val p = permissions ?: return null
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(kind); out.writeInt(VERSION); out.writeUTF(token)
            out.uuid(p.session); out.uuid(p.world); out.writeLong(p.revision)
            out.writeLong(id); out.writeByte(action.kind.ordinal)
            out.writeInt(action.pos.x); out.writeInt(action.pos.y); out.writeInt(action.pos.z)
            out.writeInt(action.blockId); out.writeInt(action.metadata)
        }
        reply = null
        return bytes.toByteArray()
    }

    companion object {
        const val VERSION = 1
        const val SNAPSHOT = "BotNavigationSnapshot"
        const val DELTA = "BotNavigationDelta"
        const val CHECK = "BotNavigationCheck"
        const val CANCEL = "BotNavigationCancel"
        const val RESULT = "BotNavigationResult"
        private fun DataInputStream.uuid() = UUID(readLong(), readLong())
        private fun DataOutputStream.uuid(id: UUID) { writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
        private fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) }
        private fun DataInputStream.positions(): Set<BlockPos> =
            (0 until count(2500)).map { BlockPos(readInt(), readInt(), readInt()) }.toSet()
    }
}
