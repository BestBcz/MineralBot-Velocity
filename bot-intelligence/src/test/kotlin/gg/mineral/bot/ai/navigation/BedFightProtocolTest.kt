package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BedFightProtocolTest {
    private val golden="001a426f744e617669676174696f6e4265644669676874537461746500000001000174000000000000000100000000000000020000000000000003000000000000000400000000000000010100000000010000000101000000020000000100000040000000030000000200000040000000030000000200000000000000050000000000000006010000003c000000000000000700000000000000080000000000"
    private fun bytes()=golden.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val permissions=NavigationPermissions(UUID(1,2),UUID(3,4),1,true,false,
        NavBox(-10.0,0.0,-10.0,10.0,255.0,10.0),50,emptySet(),emptySet())
    private fun state()=BedFightStateWire.decode(bytes())!!.state
    @Test fun `Java BedFight snapshot and Kotlin encoding share fixed bytes`() {
        val decoded=BedFightStateWire.decode(bytes())!!
        assertEquals("t",decoded.token); assertEquals(UUID(1,2),decoded.state.session)
        assertEquals(setOf(BlockPos(1,64,3),BlockPos(2,64,3)),decoded.state.beds.single().blocks)
        assertEquals(BedFightLife.RESPAWNING,decoded.state.opponents.first().life)
        assertEquals(60,decoded.state.opponents.first().respawnTicks)
        assertArrayEquals(bytes(),BedFightStateWire.encode("t",decoded.state))
    }
    @Test fun `out of order duplicate old token and wrong session cannot refresh state`() {
        var now=0L
        val store=BedFightStateStore { now }; store.bind("t")
        store.receive(bytes(),permissions); assertEquals(1L,store.current(permissions)!!.sequence)
        store.receive(BedFightStateWire.encode("t",state().copy(sequence=3)),permissions)
        now=1_999
        store.receive(BedFightStateWire.encode("t",state().copy(sequence=2)),permissions)
        store.receive(BedFightStateWire.encode("old",state().copy(sequence=4)),permissions)
        store.receive(BedFightStateWire.encode("t",state().copy(sequence=4,session=UUID(9,9))),permissions)
        assertEquals(3L,store.current(permissions)!!.sequence)
        now=2_000; assertNull(store.current(permissions))
    }
    @Test fun `world changes rebinding missing permissions and expiry disable bed objectives`() {
        var now=0L
        val store=BedFightStateStore { now }; store.bind("t"); store.receive(bytes(),null)
        assertNull(store.current(null)); assertNotNull(store.current(permissions))
        assertNull(store.current(permissions.copy(world=UUID(3,5))))
        store.bind("new"); store.receive(bytes(),permissions); assertNull(store.current(permissions))
        store.bind("t"); store.receive(bytes(),permissions)
        now=2_000; assertNull(store.current(permissions))
        store.bind(""); store.receive(bytes(),permissions); assertNull(store.current(permissions))
    }
    @Test fun `truncated unsupported trailing and invalid life packets publish nothing`() {
        val truncated=bytes().copyOf(80)
        val version=bytes(); version[31]=2
        val life=bytes(); life[life.size-5]=3
        assertNull(BedFightStateWire.decode(truncated))
        assertNull(BedFightStateWire.decode(version))
        assertNull(BedFightStateWire.decode(life))
        assertNull(BedFightStateWire.decode(bytes()+byteArrayOf(0)))
    }
}
