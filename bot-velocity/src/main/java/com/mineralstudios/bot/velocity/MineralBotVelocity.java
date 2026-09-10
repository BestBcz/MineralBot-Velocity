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

@Plugin(id = "bot-velocity", name = "MineralBotVelocity", version = "6.7-SNAPSHOT", description = "Mineral-Bot integration for Velocity", authors = {
        "BestBcz" })
public class MineralBotVelocity {
    private static final String DEFAULT_SECRET_FILE = "../../forwarding.secret";

    private static final int DEFAULT_GAME_LOOP_WORKERS = Math.max(
            2,
            Math.min(8, Runtime.getRuntime().availableProcessors()));

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
                config.forwarding(),
                config.gameLoopWorkers(),
                config.timingDiagnostics(),
                config.velocityInputRecoveryEnabled()
        );

        // Register Velocity and PacketEvents listeners
        server.getEventManager().register(this, botManager);
        PacketEvents.getAPI().getEventManager().registerListener(new BotPacketDiagnosticsListener(botManager));

        logger.info(
                "MineralBotVelocity has been initialized! guide-enabled={}, connection-mode=direct-backend-bungeeguard, "
                        + "game-loop-workers={}, timing-diagnostics={}, velocity-input-recovery-enabled={}",
                config.guideEnabled(),
                config.gameLoopWorkers(),
                config.timingDiagnostics(),
                config.velocityInputRecoveryEnabled()
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
            String secretFile = properties.getProperty("bungeeguard-secret-file", DEFAULT_SECRET_FILE);
            String forwardedIp = properties.getProperty("bot-forwarded-ip", "127.0.0.1").trim();
            int gameLoopWorkers = parsePositiveInt(
                    "game-loop-workers",
                    properties.getProperty("game-loop-workers"),
                    DEFAULT_GAME_LOOP_WORKERS,
                    64);
            boolean timingDiagnostics = Boolean.parseBoolean(
                    properties.getProperty("timing-diagnostics", "false"));
            boolean velocityInputRecoveryEnabled = Boolean.parseBoolean(
                    properties.getProperty("velocity-input-recovery-enabled", "false"));

            boolean needsWrite =
                    properties.getProperty("guide-enabled") == null
                            || properties.getProperty("bungeeguard-secret-file") == null
                            || properties.getProperty("bot-forwarded-ip") == null
                            || properties.getProperty("game-loop-workers") == null
                            || properties.getProperty("timing-diagnostics") == null
                            || properties.getProperty("velocity-input-recovery-enabled") == null;

            properties.setProperty("guide-enabled", Boolean.toString(guideEnabled));
            properties.setProperty("bungeeguard-secret-file", secretFile);
            properties.setProperty("bot-forwarded-ip", forwardedIp);
            needsWrite |= properties.remove("bot-connect-host") != null;
            needsWrite |= properties.remove("bot-connect-port") != null;
            properties.setProperty("game-loop-workers", Integer.toString(gameLoopWorkers));
            properties.setProperty("timing-diagnostics", Boolean.toString(timingDiagnostics));
            properties.setProperty(
                    "velocity-input-recovery-enabled",
                    Boolean.toString(velocityInputRecoveryEnabled));

            if (!Files.exists(configPath) || needsWrite) {
                try (OutputStream output = Files.newOutputStream(configPath)) {
                    properties.store(output, "MineralBotVelocity configuration");
                }
            }

            return new BotVelocityConfig(
                    guideEnabled,
                    loadForwarding(secretFile, forwardedIp),
                    gameLoopWorkers,
                    timingDiagnostics,
                    velocityInputRecoveryEnabled);
        } catch (IOException e) {
            logger.error("Failed to load bot-velocity config from {}", configPath, e);
            return new BotVelocityConfig(
                    true,
                    null,
                    DEFAULT_GAME_LOOP_WORKERS,
                    false,
                    false);
        }
    }

    private int parsePositiveInt(String key, String rawValue, int defaultValue, int maximum) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value < 1 || value > maximum) {
                logger.warn("{} {} is out of range. Falling back to {}.", key, rawValue, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException e) {
            logger.warn("{} {} is invalid. Falling back to {}.", key, rawValue, defaultValue);
            return defaultValue;
        }
    }

    private gg.mineral.bot.base.client.instance.BungeeGuardForwarding loadForwarding(String file, String ip) {
        try {
            var forwarding = gg.mineral.bot.base.client.instance.BungeeGuardForwarding.load(
                    dataDirectory.resolve(file).normalize(), ip);
            logger.info("BungeeGuard secret loaded");
            return forwarding;
        } catch (Exception e) {
            logger.error("Direct backend connections disabled: check bungeeguard-secret-file and bot-forwarded-ip.");
            return null;
        }
    }
    private static final class BotVelocityConfig {
        private final boolean guideEnabled;
        private final gg.mineral.bot.base.client.instance.BungeeGuardForwarding forwarding;
        private final int gameLoopWorkers;
        private final boolean timingDiagnostics;
        private final boolean velocityInputRecoveryEnabled;

        private BotVelocityConfig(
                boolean guideEnabled,
                gg.mineral.bot.base.client.instance.BungeeGuardForwarding forwarding,
                int gameLoopWorkers,
                boolean timingDiagnostics,
                boolean velocityInputRecoveryEnabled
        ) {
            this.guideEnabled = guideEnabled;
            this.forwarding = forwarding;
            this.gameLoopWorkers = gameLoopWorkers;
            this.timingDiagnostics = timingDiagnostics;
            this.velocityInputRecoveryEnabled = velocityInputRecoveryEnabled;
        }

        private boolean guideEnabled() {
            return guideEnabled;
        }

        private gg.mineral.bot.base.client.instance.BungeeGuardForwarding forwarding() {
            return forwarding;
        }
        private int gameLoopWorkers() {
            return gameLoopWorkers;
        }

        private boolean timingDiagnostics() {
            return timingDiagnostics;
        }

        private boolean velocityInputRecoveryEnabled() {
            return velocityInputRecoveryEnabled;
        }
    }
}
