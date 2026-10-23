package com.mineralstudios.bot.velocity

import com.google.common.collect.ArrayListMultimap
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import com.velocitypowered.api.scheduler.Scheduler
import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.base.client.instance.ClientInstance
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Proxy as ReflectionProxy
import java.net.Proxy
import java.nio.file.Path
import java.util.UUID

class PartyMatchStartIntegrationTest {
    @Test fun `malformed start cannot overwrite a valid matches target or friendlies`(@TempDir dir: Path) {
        val task = proxy<ScheduledTask> { _, _ -> null }
        val builder = proxy<Scheduler.TaskBuilder> { name, _ -> if (name == "schedule") task else null }
        val scheduler = proxy<Scheduler> { name, _ -> if (name == "buildTask") builder else null }
        val server = proxy<ProxyServer> { name, _ -> if (name == "getScheduler") scheduler else null }
        val manager = VelocityBotManager(Any(), server, LoggerFactory.getLogger(javaClass), null, 1, false, false)
        val config = BotConfiguration(friendlyUUIDs = mutableSetOf())
        val bot = ClientInstance(config, 800, 600, false, false, dir.toFile(), dir.toFile(), dir.toFile(),
            Proxy.NO_PROXY, "1.7.10", ArrayListMultimap.create<String, String>(), "1.7.10")
        val bots = map<ClientInstance>(manager, "activeBots")
        val tokens = map<String>(manager, "botRequestTokens")
        val targets = map<UUID>(manager, "botTargets")
        val owner = UUID(0, 1)
        val target = UUID(0, 2)
        val ally = UUID(0, 3)
        bots[config.uuid] = bot
        tokens[config.uuid] = "party-token"
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeUTF("BotDuelStarted"); writeUTF(owner.toString()); writeUTF(config.uuid.toString())
            writeUTF("NODEBUFF"); writeUTF("normal"); writeUTF("party-token")
            writeUTF(target.toString()); writeInt(1); writeUTF(ally.toString())
        }
        val receive = VelocityBotManager::class.java.getDeclaredMethod("handleBotDuelStarted", ByteArray::class.java)
            .apply { isAccessible = true }
        try {
            receive.invoke(manager, bytes.toByteArray())
            assertEquals(target, targets[config.uuid])
            assertEquals(setOf(ally), config.friendlyUUIDs)
            val validSnapshot = config.friendlyUUIDs
            receive.invoke(manager, bytes.toByteArray().copyOf(bytes.size() - 1))
            assertEquals(target, targets[config.uuid])
            assertSame(validSnapshot, config.friendlyUUIDs)
            assertEquals(setOf(ally), map<Set<UUID>>(manager, "botDeclaredFriendlyUuids")[config.uuid])
        } finally {
            bots.clear()
            manager.onProxyShutdown(null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> map(manager: VelocityBotManager, field: String): MutableMap<UUID, T> =
        VelocityBotManager::class.java.getDeclaredField(field).let {
            it.isAccessible = true
            it.get(manager) as MutableMap<UUID, T>
        }

    private inline fun <reified T> proxy(crossinline values: (String, Array<out Any?>?) -> Any?): T =
        ReflectionProxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, method, args ->
            values(method.name, args) ?: if (method.returnType.isInstance(p)) p else null
        } as T
}
