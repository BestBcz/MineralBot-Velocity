package com.mineralstudios.bot.velocity;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.io.File;

import gg.mineral.bot.base.client.instance.ClientInstance;
import gg.mineral.bot.api.configuration.BotConfiguration;
import gg.mineral.bot.impl.config.BotGlobalConfig;
import com.google.common.collect.ArrayListMultimap;
import java.net.Proxy;

public class VelocityBotManager {

    private final Object plugin;
    private final ProxyServer server;
    private final Logger logger;
    private static final String SUB_CHANNEL_BOT_DUEL = "BotDuel";

    public VelocityBotManager(Object plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        ChannelIdentifier identifier = event.getIdentifier();
        if (!(identifier instanceof MinecraftChannelIdentifier))
            return;

        MinecraftChannelIdentifier mcIdentifier = (MinecraftChannelIdentifier) identifier;
        if (!mcIdentifier.getId().equals("bungeecord") && !mcIdentifier.getId().equals("main"))
            return;

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String subChannel = in.readUTF();

        if (SUB_CHANNEL_BOT_DUEL.equals(subChannel)) {
            try {
                String playerUUIDStr = in.readUTF();
                String serverName = in.readUTF();
                String kitType = in.readUTF();

                UUID playerUUID = UUID.fromString(playerUUIDStr);

                logger.info("Received BotDuel request: Player={}, Server={}, Kit={}", playerUUID, serverName, kitType);

                createAndConnectBot(playerUUID, serverName, kitType);
            } catch (Exception e) {
                logger.error("Failed to parse BotDuel message", e);
            }
        }
    }

    private void createAndConnectBot(UUID playerUUID, String serverName, String kitType) {
        server.getScheduler().buildTask(plugin, () -> {
            try {
                // Get Server Info
                var serverInfo = server.getServer(serverName).orElse(null);
                if (serverInfo == null) {
                    logger.error("Server {} not found", serverName);
                    return;
                }

                java.net.InetSocketAddress address = serverInfo.getServerInfo().getAddress();

                // Configure Bot
                // Note: BotGlobalConfig properties are const val, assuming defaults are correct
                // (optimized, headless)

                BotConfiguration config = new BotConfiguration();
                config.setUuid(UUID.randomUUID());
                config.setUsername("Bot_" + kitType);
                config.setDebug(true);

                // Create ClientInstance
                // Use a temporary run directory
                File runDir = new File("bot-run/" + config.getUuid());
                runDir.mkdirs();

                ClientInstance bot = new ClientInstance(
                        config,
                        800, 600, false, false,
                        runDir,
                        new File("assets"), // Need logic to provide real assets if needed
                        new File("resourcepacks"),
                        Proxy.NO_PROXY,
                        "1.7.10",
                        ArrayListMultimap.create(),
                        "1.7.10");

                // Set Connection Info
                bot.setServer(address.getHostString(), address.getPort());

                // Initialize
                logger.info("Starting bot instance for {}", config.getUsername());

                // bot.run() calls startGame(), which initializes the client.
                // Since this runs in a scheduled task, we need to be careful about blocking.
                // However, startGame() should return quickly if optimized/headless.
                bot.run();

                // Schedule Game Loop
                server.getScheduler().buildTask(plugin, () -> {
                    if (bot.isRunning()) {
                        try {
                            bot.runGameLoop();
                        } catch (Exception e) {
                            logger.error("Error in bot game loop", e);
                        }
                    }
                }).repeat(50, TimeUnit.MILLISECONDS).schedule();

            } catch (Exception e) {
                logger.error("Failed to start bot", e);
            }
        }).schedule();
    }
}
