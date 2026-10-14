package gg.mineral.bot.api.navigation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

enum class BedFightLife { ALIVE, RESPAWNING, ELIMINATED }
data class EnemyBed(val team: Int, val alive: Boolean, val blocks: Set<BlockPos>)
data class BedFightOpponent(val uuid: UUID, val life: BedFightLife, val respawnTicks: Int = 0)
data class BedFightState(val session: UUID, val world: UUID, val sequence: Long,
                         val active: Boolean, val selfRespawning: Boolean,
                         val beds: List<EnemyBed>, val opponents: List<BedFightOpponent>)

/** Independent full snapshots; navigation permission revision numbers are never advanced here. */
object BedFightStateWire {
    const val CHANNEL = "BotNavigationBedFightState"
    const val VERSION = 1
    const val MAX_AGE_MILLIS = 2_000L
    data class Envelope(val token: String, val state: BedFightState)

    fun encode(token: String, state: BedFightState): ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(CHANNEL); out.writeInt(VERSION); out.writeUTF(token)
            out.uuid(state.session); out.uuid(state.world); out.writeLong(state.sequence)
            out.writeBoolean(state.active); out.writeBoolean(state.selfRespawning)
            out.writeInt(state.beds.size)
            for (bed in state.beds.sortedBy { it.team }) {
                out.writeInt(bed.team); out.writeBoolean(bed.alive); out.writeInt(bed.blocks.size)
                for (p in bed.blocks.sortedWith(compareBy<BlockPos> { it.x }.thenBy { it.y }.thenBy { it.z })) {
                    out.writeInt(p.x); out.writeInt(p.y); out.writeInt(p.z)
                }
            }
            out.writeInt(state.opponents.size)
            for (opponent in state.opponents.sortedBy { it.uuid.toString() }) {
                out.uuid(opponent.uuid); out.writeByte(opponent.life.ordinal); out.writeInt(opponent.respawnTicks)
            }
        }
        require(bytes.size()<=32767)
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): Envelope? = try {
        require(bytes.size<=32767)
        val input=DataInputStream(ByteArrayInputStream(bytes))
        require(input.readUTF()==CHANNEL && input.readInt()==VERSION)
        val token=input.readUTF(); val session=input.uuid(); val world=input.uuid(); val sequence=input.readLong()
        require(sequence>0)
        val active=input.readBoolean(); val selfRespawning=input.readBoolean()
        val beds=List(input.count(16)) {
            val team=input.readInt().also { require(it>=0) }
            val alive=input.readBoolean()
            val blocks=List(input.count(2)) { BlockPos(input.readInt(),input.readInt().also { require(it in 0..255) },input.readInt()) }.toSet()
            require(!alive || blocks.isNotEmpty())
            EnemyBed(team,alive,blocks)
        }
        val opponents=List(input.count(128)) {
            val uuid=input.uuid()
            val life=BedFightLife.entries[input.readUnsignedByte().also { require(it in 0..2) }]
            val ticks=input.readInt().also { require(it in 0..60 && (life==BedFightLife.RESPAWNING || it==0)) }
            BedFightOpponent(uuid,life,ticks)
        }
        require(input.available()==0 && beds.map { it.team }.distinct().size==beds.size &&
            opponents.map { it.uuid }.distinct().size==opponents.size)
        Envelope(token,BedFightState(session,world,sequence,active,selfRespawning,beds,opponents))
    } catch (_: Exception) { null }

    private fun DataInputStream.count(max: Int)=readInt().also { require(it in 0..max) }
    private fun DataInputStream.uuid()=UUID(readLong(),readLong())
    private fun DataOutputStream.uuid(uuid: UUID) { writeLong(uuid.mostSignificantBits); writeLong(uuid.leastSignificantBits) }
}

class BedFightStateStore(private val millis: () -> Long = { System.nanoTime()/1_000_000 }) {
    private var token=""
    private var state: BedFightState?=null
    private var receivedAt=0L
    fun bind(token: String) { this.token=token; state=null }
    fun receive(bytes: ByteArray, permissions: NavigationPermissions?) {
        if (token.isEmpty()) return
        val envelope=BedFightStateWire.decode(bytes) ?: return
        val next=envelope.state
        if (envelope.token!=token || permissions!=null && (next.session!=permissions.session || next.world!=permissions.world)) return
        if (state?.let { next.sequence<=it.sequence }==true) return
        state=next; receivedAt=millis()
    }
    fun current(permissions: NavigationPermissions?): BedFightState? = state?.takeIf {
        permissions!=null && it.session==permissions.session && it.world==permissions.world &&
            millis()-receivedAt<BedFightStateWire.MAX_AGE_MILLIS
    }
}
