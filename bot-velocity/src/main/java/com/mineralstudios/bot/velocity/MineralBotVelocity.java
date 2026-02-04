package com.mineralstudios.bot.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder;
import org.slf4j.Logger;

@Plugin(id = "bot-velocity", name = "MineralBotVelocity", version = "1.0-SNAPSHOT", description = "Mineral-Bot integration for Velocity", authors = {
        "MineralStudios" })
public class MineralBotVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final java.nio.file.Path dataDirectory;
    private final com.velocitypowered.api.plugin.PluginContainer pluginContainer;

    public static final MinecraftChannelIdentifier PLUGIN_CHANNEL = MinecraftChannelIdentifier.create("bungeecord",
            "main");

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

        // Register Plugin Message Channel
        server.getChannelRegistrar().register(PLUGIN_CHANNEL);

        // Register Event Listener
        server.getEventManager().register(this, new VelocityBotManager(this, server, logger));

        logger.info("MineralBotVelocity has been initialized!");
    }
}
