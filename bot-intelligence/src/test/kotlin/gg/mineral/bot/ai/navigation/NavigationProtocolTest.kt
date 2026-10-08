package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.*
import java.util.UUID

class NavigationProtocolTest {
    private val snapshotHex="0015426f744e617669676174696f6e536e617073686f74000000010001740000000000000001000000000000000200000000000000030000000000000004000000000000000100000000000000010100fffffff600000000fffffff60000000a000000ff0000000a000000320000000000000002000000010000004000000003000000020000004000000003"
    private val checkHex="0012426f744e617669676174696f6e436865636b00000001000174000000000000000100000000000000020000000000000003000000000000000400000000000000010000000000000063010000000100000040000000030000002300000005"
    private fun bytes(hex: String)=hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun store()=NavigationPermissionStore().also { it.bind("t") }
    private fun header(out: DataOutputStream,kind: String,revision: Long) {
        out.writeUTF(kind); out.writeInt(1); out.writeUTF("t")
        for(v in listOf(1L,2L,3L,4L,revision))out.writeLong(v)
    }
    private fun snapshot(index: Int,count: Int,revision: Long,position: BlockPos): ByteArray {
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            header(out,NavigationPermissionStore.SNAPSHOT,revision); out.writeInt(index); out.writeInt(count)
            out.writeBoolean(true); out.writeBoolean(false)
            for(v in listOf(-10,0,-10,10,255,10,50,0,1,position.x,position.y,position.z))out.writeInt(v)
        }
        return buffer.toByteArray()
    }
    @Test fun `Java server snapshot decodes and client check matches shared golden bytes`() {
        val store=store(); store.receive(bytes(snapshotHex))
        val p=requireNotNull(store.permissions)
        assertEquals(UUID(1,2),p.session)
        assertEquals(setOf(BlockPos(1,64,3),BlockPos(2,64,3)),p.breakPositions)
        assertTrue(p.canBreak(BlockPos(1,64,3),35)); assertFalse(p.canBreak(BlockPos(3,64,3),35))
        assertFalse(p.canPlace(BlockPos(1,64,3)))
        assertArrayEquals(bytes(checkHex),store.check(99,BlockAction(BlockActionKind.PLACE,BlockPos(1,64,3),35,5,0)))
    }
    @Test fun `multipart snapshots publish atomically even when reordered and duplicated`() {
        val store=store()
        val p=BlockPos(1,2,3); val q=BlockPos(2,2,3)
        store.receive(snapshot(1,2,1,q)); store.receive(snapshot(1,2,1,q))
        assertNull(store.permissions)
        store.receive(snapshot(0,2,1,p))
        assertEquals(setOf(p,q),store.permissions!!.breakPositions)
        store.receive(snapshot(0,2,2,BlockPos(3,2,3)))
        assertEquals(setOf(p,q),store.permissions!!.breakPositions)
    }
    @Test fun `a delta gap disables edits until a newer complete snapshot`() {
        val store=store(); store.receive(bytes(snapshotHex))
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).use { out -> header(out,NavigationPermissionStore.DELTA,3); out.writeLong(2); out.writeInt(0); out.writeInt(0) }
        store.receive(buffer.toByteArray()); assertNull(store.permissions)
        store.receive(snapshot(0,1,4,BlockPos(4,2,3)))
        assertEquals(4L,store.permissions!!.revision)
    }
    @Test fun `old matches unsupported versions and truncated packets cannot grant edits`() {
        val store=store(); store.bind("new-match"); store.receive(bytes(snapshotHex)); assertNull(store.permissions)
        store.bind("t"); val version=bytes(snapshotHex); version[24]=2; store.receive(version); assertNull(store.permissions)
        store.receive(bytes(snapshotHex).copyOf(90)); assertNull(store.permissions)
        store.receive(bytes(snapshotHex)); assertNotNull(store.permissions)
        store.receive(version); assertNull(store.permissions)
    }
    @Test fun `ordered deltas remove obsolete block permissions`() {
        val store=store(); store.receive(bytes(snapshotHex))
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            header(out,NavigationPermissionStore.DELTA,2); out.writeLong(1)
            out.writeInt(1); out.writeInt(3); out.writeInt(64); out.writeInt(3)
            out.writeInt(1); out.writeInt(1); out.writeInt(64); out.writeInt(3)
        }
        store.receive(buffer.toByteArray())
        assertFalse(store.permissions!!.canBreak(BlockPos(1,64,3),35))
        assertTrue(store.permissions!!.canBreak(BlockPos(3,64,3),35))
    }
    @Test fun `server marked generated blocks and original defence are accepted regardless of material`() {
        val store=store(); store.receive(bytes(snapshotHex))
        val p=store.permissions!!
        for(id in listOf(35,49,4,5,121)) assertTrue(p.canBreak(BlockPos(1,64,3),id))
        assertFalse(p.canBreak(BlockPos(4,64,3),26)) // an unlisted own bed cannot be excavated
        val cave=p.copy(breakAll=true)
        assertTrue(cave.canBreak(BlockPos(4,64,3),1))
        assertFalse(cave.canBreak(BlockPos(11,64,3),1))
        assertFalse(cave.copy(building=false).canBreak(BlockPos(4,64,3),1))
    }
    @Test fun `world switch snapshot supersedes the old world and late old messages are ignored`() {
        val store=store(); store.receive(bytes(snapshotHex))
        val newWorld=bytes(snapshotHex)
        newWorld[61]=5 // low byte of world UUID
        newWorld[69]=2 // monotonic sequence across a world switch using the same token
        store.receive(newWorld)
        assertEquals(UUID(3,5),store.permissions!!.world)
        store.receive(bytes(snapshotHex))
        assertEquals(UUID(3,5),store.permissions!!.world)
    }
}
