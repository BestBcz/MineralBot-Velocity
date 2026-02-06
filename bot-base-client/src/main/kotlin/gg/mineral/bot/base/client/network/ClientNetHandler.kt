package gg.mineral.bot.base.client.network

import gg.mineral.bot.base.client.player.controller.BotController
import java.nio.charset.StandardCharsets
import net.minecraft.client.ClientBrandRetriever
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiDownloadTerrain
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.multiplayer.WorldClient
import net.minecraft.client.network.NetHandlerPlayClient
import net.minecraft.network.NetworkManager
import net.minecraft.network.play.client.C17PacketCustomPayload
import net.minecraft.network.play.server.S01PacketJoinGame
import net.minecraft.world.WorldSettings

class ClientNetHandler(mc: Minecraft, guiScreen: GuiScreen?, netManager: NetworkManager) :
        NetHandlerPlayClient(mc, guiScreen, netManager) {
    override fun handleJoinGame(packet: S01PacketJoinGame) {
        val gameController = this.gameController
        gameController.playerController = BotController(gameController, this)

        val clientWorldController =
                WorldClient(
                        gameController,
                        this,
                        WorldSettings(
                                0L,
                                packet.func_149198_e(),
                                false,
                                packet.func_149195_d(),
                                packet.func_149196_i()
                        ),
                        packet.func_149194_f(),
                        packet.func_149192_g(),
                        gameController.mcProfiler
                )

        setClientWorldController(clientWorldController)
        clientWorldController.isClient = true
        gameController.loadWorld(clientWorldController)
        val thePlayer = gameController.thePlayer

        if (thePlayer != null) thePlayer.dimension = packet.func_149194_f()
        gameController.displayGuiScreen(GuiDownloadTerrain(gameController, this))
        thePlayer?.setEntityId(packet.func_149197_c())
        this.currentServerMaxPlayers = packet.func_149193_h()
        gameController.playerController.setGameType(packet.func_149198_e())
        gameController.gameSettings.sendSettingsToServer()
        val netManager = this.networkManager
        netManager.scheduleOutboundPacket(
                C17PacketCustomPayload(
                        "MC|Brand",
                        ClientBrandRetriever.getClientModName().toByteArray(StandardCharsets.UTF_8)
                )
        )
    }

    override fun handleCustomPayload(
            packet: net.minecraft.network.play.server.S3FPacketCustomPayload
    ) {
        if ("MineralBot" == packet.func_149169_c()) {
            val data = packet.func_149168_d()
            if (data != null && data.isNotEmpty()) {
                try {
                    val buf = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
                    val subChannel = buf.readUTF()
                    if ("BotKnockback" == subChannel) {
                        val name = buf.readUTF()
                        println("[BotDebug] Received BotKnockback packet: $name")
                        val friction = buf.readDouble()
                        val horizontal = buf.readDouble()
                        val vertical = buf.readDouble()
                        val verticalLimit = buf.readDouble()
                        val extraHorizontal = buf.readDouble()
                        val extraVertical = buf.readDouble()
                        val recoilMultiplier = buf.readDouble()

                        val profile =
                                gg.mineral.bot.base.client.profile.KnockbackProfile(
                                        name,
                                        friction,
                                        horizontal,
                                        vertical,
                                        verticalLimit,
                                        extraHorizontal,
                                        extraVertical,
                                        recoilMultiplier
                                )

                        val player = gameController.thePlayer
                        if (player != null) {
                            player.setKnockbackProfile(profile)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        super.handleCustomPayload(packet)
    }
}
