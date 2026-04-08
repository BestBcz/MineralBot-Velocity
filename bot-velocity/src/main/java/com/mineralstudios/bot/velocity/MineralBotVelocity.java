package com.mineralstudios.bot.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Plugin(id = "bot-velocity", name = "MineralBotVelocity", version = "1.0-SNAPSHOT", description = "Mineral-Bot integration for Velocity", authors = {
        "MineralStudios" })
public class MineralBotVelocity {
    private static final String DEFAULT_BOT_CONNECT_HOST = "127.0.0.1";
    private static final int DEFAULT_BOT_CONNECT_PORT = 25567;

    private final ProxyServer server;
    private final Logger logger;
    private final java.nio.file.Path dataDirectory;
    private final com.velocitypowered.api.plugin.PluginContainer pluginContainer;

    public static final MinecraftChannelIdentifier PLUGIN_CHANNEL = MinecraftChannelIdentifier.create("bungeecord",
            "main");
    public static final LegacyChannelIdentifier MINERAL_BOT_CHANNEL = new LegacyChannelIdentifier("MineralBot");

    @Inject
    public MineralBotVelocity(ProxyServer server, Logger logger,
            @com.velocitypowered.api.plugin.annotation.DataDirectory java.nio.file.Path dataDirectory,
            com.velocitypowered.api.plugin.PluginContainer pluginContainer) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.pluginContainer = pluginContainer;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Initialize PacketEvents
        PacketEvents.setAPI(VelocityPacketEventsBuilder.build(server, pluginContainer, logger, dataDirectory));
        PacketEvents.getAPI().getSettings().checkForUpdates(false);
        PacketEvents.getAPI().load();
        PacketEvents.getAPI().init();

        // Initialize Mineral Bot API
        gg.mineral.bot.base.client.BotImpl.Companion.init();

        // Register Plugin Message Channel
        server.getChannelRegistrar().register(PLUGIN_CHANNEL);
        server.getChannelRegistrar().register(MINERAL_BOT_CHANNEL);

        BotVelocityConfig config = loadConfig();

        VelocityBotManager botManager = new VelocityBotManager(
                this,
                server,
                logger,
                config.guideEnabled(),
                config.botConnectHost(),
                config.botConnectPort()
        );

        // Register Velocity and PacketEvents listeners
        server.getEventManager().register(this, botManager);
        PacketEvents.getAPI().getEventManager().registerListener(new BotPacketDiagnosticsListener(botManager));

        logger.info(
                "MineralBotVelocity has been initialized! guide-enabled={}, bot-connect={}:{}",
                config.guideEnabled(),
                config.botConnectHost(),
                config.botConnectPort()
        );
    }

    private BotVelocityConfig loadConfig() {
        Path configPath = dataDirectory.resolve("config.properties");
        Properties properties = new Properties();

        try {
            Files.createDirectories(dataDirectory);

            if (Files.exists(configPath)) {
                try (InputStream input = Files.newInputStream(configPath)) {
                    properties.load(input);
                }
            }

            boolean guideEnabled = Boolean.parseBoolean(properties.getProperty("guide-enabled", "true"));
            String botConnectHost = properties.getProperty("bot-connect-host", DEFAULT_BOT_CONNECT_HOST).trim();
            if (botConnectHost.isEmpty()) {
                botConnectHost = DEFAULT_BOT_CONNECT_HOST;
            }
            int botConnectPort = parsePort(properties.getProperty("bot-connect-port"), DEFAULT_BOT_CONNECT_PORT);

            boolean needsWrite =
                    properties.getProperty("guide-enabled") == null
                            || properties.getProperty("bot-connect-host") == null
                            || properties.getProperty("bot-connect-port") == null;

            properties.setProperty("guide-enabled", Boolean.toString(guideEnabled));
            properties.setProperty("bot-connect-host", botConnectHost);
            properties.setProperty("bot-connect-port", Integer.toString(botConnectPort));

            if (!Files.exists(configPath) || needsWrite) {
                try (OutputStream output = Files.newOutputStream(configPath)) {
                    properties.store(output, "MineralBotVelocity configuration");
                }
            }

            return new BotVelocityConfig(guideEnabled, botConnectHost, botConnectPort);
        } catch (IOException e) {
            logger.error("Failed to load bot-velocity config from {}", configPath, e);
            return new BotVelocityConfig(true, DEFAULT_BOT_CONNECT_HOST, DEFAULT_BOT_CONNECT_PORT);
        }
    }

    private int parsePort(String rawPort, int defaultPort) {
        if (rawPort == null || rawPort.trim().isEmpty()) {
            return defaultPort;
        }

        try {
            int port = Integer.parseInt(rawPort.trim());
            if (port < 1 || port > 65535) {
                logger.warn("bot-connect-port {} is out of range. Falling back to {}.", rawPort, defaultPort);
                return defaultPort;
            }
            return port;
        } catch (NumberFormatException e) {
            logger.warn("bot-connect-port {} is invalid. Falling back to {}.", rawPort, defaultPort);
            return defaultPort;
        }
    }

    private static final class BotVelocityConfig {
        private final boolean guideEnabled;
        private final String botConnectHost;
        private final int botConnectPort;

        private BotVelocityConfig(boolean guideEnabled, String botConnectHost, int botConnectPort) {
            this.guideEnabled = guideEnabled;
            this.botConnectHost = botConnectHost;
            this.botConnectPort = botConnectPort;
        }

        private boolean guideEnabled() {
            return guideEnabled;
        }

        private String botConnectHost() {
            return botConnectHost;
        }

        private int botConnectPort() {
            return botConnectPort;
        }
    }
}
