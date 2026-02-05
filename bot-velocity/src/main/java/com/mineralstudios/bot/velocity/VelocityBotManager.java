package com.mineralstudios.bot.velocity;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.io.File;

import gg.mineral.bot.base.client.instance.ClientInstance;
import gg.mineral.bot.api.configuration.BotConfiguration;
import gg.mineral.bot.ai.goal.practice.PracticeAI;
import com.google.common.collect.ArrayListMultimap;
import java.net.Proxy;

public class VelocityBotManager {

    private final Object plugin;
    private final ProxyServer server;
    private final Logger logger;

    private static final String SUB_CHANNEL_BOT_DUEL = "BotDuel";
    private static final String SUB_CHANNEL_BOT_DUEL_STARTED = "BotDuelStarted";
    private static final String SUB_CHANNEL_BOT_DISCONNECT = "BotDisconnect";

    // Track active bots: Bot UUID -> ClientInstance
    private final Map<UUID, ClientInstance> activeBots = new ConcurrentHashMap<>();

    // Track bot targets: Bot UUID -> Target Player UUID
    private final Map<UUID, UUID> botTargets = new ConcurrentHashMap<>();

    // Track kit types: Bot UUID -> Kit Type
    private final Map<UUID, String> kitTypes = new ConcurrentHashMap<>();

    public VelocityBotManager(Object plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        ChannelIdentifier identifier = event.getIdentifier();
        logger.info("DEBUG: PluginMessageEvent received. ID: '{}' (Class: {})", identifier.getId(),
                identifier.getClass().getName());

        // Check for MineralBot channel
        boolean isMineralBot = identifier.getId().equals("MineralBot") || identifier.getId().equals("mineralbot:main");

        if (!isMineralBot) {
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String subChannel;
        try {
            subChannel = in.readUTF();
            logger.info("Plugin Message received on channel: {} with subchannel: {}", identifier.getId(), subChannel);
        } catch (Exception e) {
            logger.error("Failed to read subchannel from plugin message on " + identifier.getId(), e);
            return;
        }

        switch (subChannel) {
            case SUB_CHANNEL_BOT_DUEL:
                handleBotDuelRequest(in);
                break;
            case SUB_CHANNEL_BOT_DUEL_STARTED:
                handleBotDuelStarted(in);
                break;
            case SUB_CHANNEL_BOT_DISCONNECT:
                handleBotDisconnect(in);
                break;
            default:
                logger.info("Ignored subchannel: {}", subChannel);
        }
    }

    private void handleBotDuelRequest(ByteArrayDataInput in) {
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

    private void handleBotDuelStarted(ByteArrayDataInput in) {
        try {
            String playerUUIDStr = in.readUTF();
            String botUUIDStr = in.readUTF();
            String kitType = in.readUTF();

            UUID playerUUID = UUID.fromString(playerUUIDStr);
            UUID botUUID = UUID.fromString(botUUIDStr);

            logger.info("BotDuel started: Player={}, Bot={}, Kit={}", playerUUID, botUUID, kitType);

            // Store target for combat AI
            botTargets.put(botUUID, playerUUID);
            kitTypes.put(botUUID, kitType);

            // Enable combat AI for this bot using PracticeAI
            ClientInstance bot = activeBots.get(botUUID);
            if (bot != null) {
                logger.info("Configuring combat AI for bot {} with kit type: {}", botUUID, kitType);
                // Configure the bot with appropriate goals for this kit type
                PracticeAI.INSTANCE.configureBotForKit(bot, kitType);
            }
        } catch (Exception e) {
            logger.error("Failed to parse BotDuelStarted message", e);
        }
    }

    private void handleBotDisconnect(ByteArrayDataInput in) {
        try {
            String botUUIDStr = in.readUTF();
            UUID botUUID = UUID.fromString(botUUIDStr);

            logger.info("Received BotDisconnect request for: {}", botUUID);

            ClientInstance bot = activeBots.remove(botUUID);
            botTargets.remove(botUUID);
            kitTypes.remove(botUUID);

            if (bot != null) {
                try {
                    bot.shutdown();
                    logger.info("Bot {} disconnected successfully", botUUID);
                } catch (Exception e) {
                    logger.error("Error shutting down bot {}", botUUID, e);
                }
            }

            // Also disconnect through Velocity
            server.getPlayer(botUUID).ifPresent(player -> {
                player.disconnect(net.kyori.adventure.text.Component.text("§eMatch ended. GG!"));
            });
        } catch (Exception e) {
            logger.error("Failed to parse BotDisconnect message", e);
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

                // Configure Bot
                BotConfiguration config = new BotConfiguration();
                UUID botUUID = UUID.randomUUID();
                config.setUuid(botUUID);
                config.setUsername("Bot_" + kitType);
                config.setDebug(true);

                // Create ClientInstance
                File runDir = new File("bot-run/" + config.getUuid());
                runDir.mkdirs();

                ClientInstance bot = new ClientInstance(
                        config,
                        800, 600, false, false,
                        runDir,
                        new File("assets"),
                        new File("resourcepacks"),
                        Proxy.NO_PROXY,
                        "1.7.10",
                        ArrayListMultimap.create(),
                        "1.7.10");

                // Set Connection Info - connect to proxy
                bot.setServer("127.0.0.1", 25565);

                // Track the bot
                activeBots.put(botUUID, bot);
                botTargets.put(botUUID, playerUUID);

                // Initialize
                logger.info("Starting bot instance for {} (UUID: {})", config.getUsername(), botUUID);

                bot.run();

                // Schedule Game Loop with Combat AI
                server.getScheduler().buildTask(plugin, () -> {
                    if (bot.isRunning()) {
                        try {
                            bot.runGameLoop();

                            // Basic Combat AI - attack nearby players
                            UUID targetUUID = botTargets.get(botUUID);
                            if (targetUUID != null) {
                                // The bot library should handle actual combat
                                // This is where you'd integrate with bot's attack methods
                                performCombatAI(bot, targetUUID);
                            }
                        } catch (Exception e) {
                            logger.error("Error in bot game loop", e);
                        }
                    } else {
                        // Bot stopped running, clean up
                        activeBots.remove(botUUID);
                        botTargets.remove(botUUID);
                    }
                }).repeat(50, TimeUnit.MILLISECONDS).schedule();

            } catch (Exception e) {
                logger.error("Failed to start bot", e);
            }
        }).schedule();
    }

    /**
     * Perform combat AI actions for the bot.
     * This method is called every game tick (50ms).
     */
    private void performCombatAI(ClientInstance bot, UUID targetUUID) {
        try {
            // The actual combat logic depends on the Mineral-Bot API
            // Basic implementation:
            // 1. Look at target player
            // 2. Move towards target if too far
            // 3. Attack when in range
            // 4. Use items (potions, pearls) strategically

            // This is a placeholder - actual implementation depends on
            // the methods available in ClientInstance for:
            // - Getting nearby entities
            // - Looking at entities
            // - Attacking
            // - Movement
            // - Item usage

            // The bot should already have basic AI if configured properly
            // in the BotConfiguration or through the intelligence module
        } catch (Exception e) {
            // Silently ignore combat AI errors to avoid spam
        }
    }

    @Subscribe
    public void onPreLogin(com.velocitypowered.api.event.connection.PreLoginEvent event) {
        String username = event.getUsername();
        // Check if the user is a Bot and connecting from localhost
        if (username.startsWith("Bot_") && event.getConnection().getRemoteAddress().getAddress().isLoopbackAddress()) {
            logger.info("Bypassing authentication for internal bot: {}", username);
            event.setResult(
                    com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }

    /**
     * Shutdown all active bots.
     */
    public void shutdownAllBots() {
        for (Map.Entry<UUID, ClientInstance> entry : activeBots.entrySet()) {
            try {
                entry.getValue().shutdown();
                logger.info("Shutdown bot: {}", entry.getKey());
            } catch (Exception e) {
                logger.error("Error shutting down bot {}", entry.getKey(), e);
            }
        }
        activeBots.clear();
        botTargets.clear();
    }

    /**
     * Get the number of active bots.
     */
    public int getActiveBotCount() {
        return activeBots.size();
    }
}
