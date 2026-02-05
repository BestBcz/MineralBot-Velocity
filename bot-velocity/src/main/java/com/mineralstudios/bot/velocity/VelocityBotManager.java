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
import com.mineralstudios.bot.velocity.perception.BotPerception;
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
    
    // Track bot by username: Username -> Bot UUID (for matching when server sends different UUID)
    private final Map<String, UUID> botsByUsername = new ConcurrentHashMap<>();
    
    // Track server-assigned UUID to our UUID: Server UUID -> Our Bot UUID
    private final Map<UUID, UUID> serverUuidToOurUuid = new ConcurrentHashMap<>();

    // Perception and Combat systems
    private final BotPacketListener packetListener;
    private final BotCombatTask combatTask;

    public VelocityBotManager(Object plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        
        // Initialize perception system
        this.packetListener = new BotPacketListener(logger);
        this.packetListener.register();
        
        // Initialize combat task system
        this.combatTask = new BotCombatTask(plugin, server, logger, packetListener);
        this.combatTask.setBotInstanceResolver(this::getBot);
        
        logger.info("VelocityBotManager initialized with perception and combat systems");
    }
    
    /**
     * Get a bot instance by UUID (tries both our UUID and server-assigned UUID).
     */
    public ClientInstance getBot(UUID botUuid) {
        ClientInstance bot = activeBots.get(botUuid);
        if (bot != null) {
            return bot;
        }
        // Try to find by server-assigned UUID
        UUID ourUuid = serverUuidToOurUuid.get(botUuid);
        if (ourUuid != null) {
            return activeBots.get(ourUuid);
        }
        return null;
    }
    
    /**
     * Resolve a UUID to our internal bot UUID.
     */
    private UUID resolveToOurUuid(UUID possiblyServerUuid) {
        // First check if it's already our UUID
        if (activeBots.containsKey(possiblyServerUuid)) {
            return possiblyServerUuid;
        }
        // Check if it's a server-assigned UUID we've mapped
        UUID ourUuid = serverUuidToOurUuid.get(possiblyServerUuid);
        if (ourUuid != null) {
            return ourUuid;
        }
        return possiblyServerUuid;
    }
    
    /**
     * Get perception data for a bot.
     */
    public BotPerception getPerception(UUID botUuid) {
        return packetListener.getPerception(resolveToOurUuid(botUuid));
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
            UUID serverBotUUID = UUID.fromString(botUUIDStr);

            logger.info("BotDuel started: Player={}, Bot(server)={}, Kit={}", playerUUID, serverBotUUID, kitType);
            
            // Try to find the bot - first check if it's our UUID
            ClientInstance bot = activeBots.get(serverBotUUID);
            UUID ourBotUUID = serverBotUUID;
            
            if (bot == null) {
                // Server sent a different UUID - try to find our bot by iterating
                // This happens when the server assigns a different UUID to the player
                logger.info("Bot not found by server UUID, searching by kit type...");
                
                for (Map.Entry<UUID, String> entry : kitTypes.entrySet()) {
                    if (entry.getValue().equalsIgnoreCase(kitType) && activeBots.containsKey(entry.getKey())) {
                        ourBotUUID = entry.getKey();
                        bot = activeBots.get(ourBotUUID);
                        
                        // Map server UUID to our UUID for future lookups
                        serverUuidToOurUuid.put(serverBotUUID, ourBotUUID);
                        logger.info("Found bot by kit type: ourUUID={}, serverUUID={}", ourBotUUID, serverBotUUID);
                        break;
                    }
                }
            }
            
            if (bot == null) {
                logger.warn("Could not find bot for BotDuelStarted. Server UUID: {}", serverBotUUID);
                logger.warn("Active bots: {}", activeBots.keySet());
                logger.warn("Kit types: {}", kitTypes);
                return;
            }

            // Store target for combat AI
            botTargets.put(ourBotUUID, playerUUID);
            kitTypes.put(ourBotUUID, kitType);

            logger.info("Configuring combat AI for bot {} with kit type: {}", ourBotUUID, kitType);
            
            // Configure the bot with appropriate goals for this kit type
            PracticeAI.INSTANCE.configureBotForKit(bot, kitType);
            
            // Start the Velocity-side combat task (perception + packet-based combat)
            // Use OUR UUID, not the server's UUID
            combatTask.startCombatTask(ourBotUUID, playerUUID, kitType);
            
            logger.info("Combat systems activated for bot {}", ourBotUUID);
        } catch (Exception e) {
            logger.error("Failed to parse BotDuelStarted message", e);
        }
    }

    private void handleBotDisconnect(ByteArrayDataInput in) {
        try {
            String botUUIDStr = in.readUTF();
            UUID serverBotUUID = UUID.fromString(botUUIDStr);

            logger.info("Received BotDisconnect request for: {}", serverBotUUID);
            
            // Resolve to our UUID
            UUID ourBotUUID = resolveToOurUuid(serverBotUUID);
            logger.info("Resolved to our UUID: {}", ourBotUUID);

            // Stop combat task first
            combatTask.stopCombatTask(ourBotUUID);

            ClientInstance bot = activeBots.remove(ourBotUUID);
            botTargets.remove(ourBotUUID);
            kitTypes.remove(ourBotUUID);
            serverUuidToOurUuid.remove(serverBotUUID);
            
            // Also remove from username map
            String username = null;
            for (Map.Entry<String, UUID> entry : botsByUsername.entrySet()) {
                if (entry.getValue().equals(ourBotUUID)) {
                    username = entry.getKey();
                    break;
                }
            }
            if (username != null) {
                botsByUsername.remove(username);
            }

            if (bot != null) {
                try {
                    bot.shutdown();
                    logger.info("Bot {} disconnected successfully", ourBotUUID);
                } catch (Exception e) {
                    logger.error("Error shutting down bot {}", ourBotUUID, e);
                }
            }

            // Also disconnect through Velocity
            server.getPlayer(serverBotUUID).ifPresent(player -> {
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
                String botUsername = "Bot_" + kitType;
                config.setUuid(botUUID);
                config.setUsername(botUsername);
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

                // Track the bot and kit type
                activeBots.put(botUUID, bot);
                botTargets.put(botUUID, playerUUID);
                kitTypes.put(botUUID, kitType);
                botsByUsername.put(botUsername, botUUID);

                // Initialize
                logger.info("Starting bot instance for {} (UUID: {})", config.getUsername(), botUUID);

                bot.run();
                
                // Register with perception system immediately (include username for UUID mapping)
                packetListener.registerBot(botUUID, botUsername);
                logger.info("Registered bot {} ({}) with perception system", botUUID, botUsername);

                // Configure AI after a short delay to ensure bot is fully connected
                server.getScheduler().buildTask(plugin, () -> {
                    try {
                        if (bot.isRunning()) {
                            logger.info("Configuring PracticeAI for bot {} with kit type: {}", botUUID, kitType);
                            PracticeAI.INSTANCE.configureBotForKit(bot, kitType);
                            logger.info("PracticeAI configured successfully for bot {}", botUUID);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to configure AI for bot {}", botUUID, e);
                    }
                }).delay(3, TimeUnit.SECONDS).schedule(); // 3 second delay to ensure connection

                // Schedule Game Loop
                server.getScheduler().buildTask(plugin, () -> {
                    if (bot.isRunning()) {
                        try {
                            bot.runGameLoop();
                        } catch (Exception e) {
                            logger.error("Error in bot game loop", e);
                        }
                    } else {
                        // Bot stopped running, clean up
                        activeBots.remove(botUUID);
                        botTargets.remove(botUUID);
                        kitTypes.remove(botUUID);
                        botsByUsername.remove(botUsername);
                    }
                }).repeat(50, TimeUnit.MILLISECONDS).schedule();

            } catch (Exception e) {
                logger.error("Failed to start bot", e);
            }
        }).schedule();
    }




    @Subscribe
    public void onPreLogin(com.velocitypowered.api.event.connection.PreLoginEvent event) {
        String username = event.getUsername();
        // Check if this is one of our bots
        if (botsByUsername.containsKey(username)) {
            logger.info("Bypassing authentication for internal bot: {}", username);
            event.setResult(com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }
}
