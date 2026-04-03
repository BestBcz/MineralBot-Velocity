package gg.mineral.bot.base.client.instance

import java.util.UUID

fun interface PacketDiagnosticsListener {
    fun onClientboundPacket(packetKey: String, entityId: Int, entityUuid: UUID?)
}
