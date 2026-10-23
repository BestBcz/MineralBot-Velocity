package gg.mineral.bot.base.client.combat

import com.google.common.collect.ArrayListMultimap
import com.mojang.authlib.GameProfile
import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.base.client.instance.ClientInstance
import net.minecraft.client.entity.EntityOtherPlayerMP
import net.minecraft.client.multiplayer.PlayerControllerMP
import net.minecraft.client.multiplayer.WorldClient
import net.minecraft.client.network.NetHandlerPlayClient
import net.minecraft.entity.Entity
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.profiler.Profiler
import net.minecraft.world.EnumDifficulty
import net.minecraft.world.WorldSettings
import net.minecraft.world.WorldType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Proxy
import java.nio.file.Path
import java.util.UUID

class FriendlyAttackProtectionTest {
    @Test fun `actual controller blocks teammate hit packets while allowing enemy attacks`(@TempDir dir: Path) {
        val config = BotConfiguration(friendlyUUIDs = mutableSetOf())
        val client = ClientInstance(config, 800, 600, false, false, dir.toFile(), dir.toFile(), dir.toFile(),
            Proxy.NO_PROXY, "1.7.10", ArrayListMultimap.create<String, String>(), "1.7.10")
        val packets = mutableListOf<Packet>()
        val handler = object : NetHandlerPlayClient(client, null, null) {
            override fun addToSendQueue(packet: Packet) { packets.add(packet) }
        }
        val world = WorldClient(client, handler,
            WorldSettings(0, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT),
            0, EnumDifficulty.NORMAL, Profiler(client))
        var localAttacks = 0
        val actor = object : EntityOtherPlayerMP(client, world, GameProfile(config.uuid, "Self")) {
            override fun attackTargetEntityWithCurrentItem(target: Entity) { localAttacks++ }
        }
        val ally = EntityOtherPlayerMP(client, world, GameProfile(UUID(0, 2), "Ally"))
        val enemy = EntityOtherPlayerMP(client, world, GameProfile(UUID(0, 3), "Enemy"))
        val controller = PlayerControllerMP(client, handler)
        val previous = config.friendlyUUIDs
        config.friendlyUUIDs = mutableSetOf(ally.uuid)
        assertTrue(previous.isEmpty())

        // Use the entity actually under the crosshair, retaining ally occlusion.
        controller.attackEntity(actor, ally)
        controller.attackEntity(actor, actor)
        assertTrue(packets.isEmpty())
        assertEquals(0, localAttacks)
        controller.attackEntity(actor, enemy)
        val attack = packets.single() as C02PacketUseEntity
        assertEquals(C02PacketUseEntity.Action.ATTACK, attack.action)
        assertEquals(enemy.entityId, attack.entityId)
        assertEquals(1, localAttacks)
    }
}
