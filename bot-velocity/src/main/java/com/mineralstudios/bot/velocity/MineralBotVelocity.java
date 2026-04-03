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

        boolean guideEnabled = loadGuideEnabled();

        VelocityBotManager botManager = new VelocityBotManager(this, server, logger, guideEnabled);

        // Register Velocity and PacketEvents listeners
        server.getEventManager().register(this, botManager);
        PacketEvents.getAPI().getEventManager().registerListener(new BotPacketDiagnosticsListener(botManager));

        logger.info("MineralBotVelocity has been initialized! guide-enabled={}", guideEnabled);
    }

    private boolean loadGuideEnabled() {
        Path configPath = dataDirectory.resolve("config.properties");
        Properties properties = new Properties();

        try {
            Files.createDirectories(dataDirectory);

            if (Files.exists(configPath)) {
                try (InputStream input = Files.newInputStream(configPath)) {
                    properties.load(input);
                }
            }

            boolean hasGuideSetting = properties.getProperty("guide-enabled") != null;
            boolean guideEnabled = Boolean.parseBoolean(properties.getProperty("guide-enabled", "true"));

            if (!Files.exists(configPath) || !hasGuideSetting) {
                properties.setProperty("guide-enabled", Boolean.toString(guideEnabled));
                try (OutputStream output = Files.newOutputStream(configPath)) {
                    properties.store(output, "MineralBotVelocity configuration");
                }
            }

            return guideEnabled;
        } catch (IOException e) {
            logger.error("Failed to load bot-velocity config from {}", configPath, e);
            return true;
        }
    }
}
