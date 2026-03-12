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
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.File;

import gg.mineral.bot.base.client.instance.ClientInstance;
import gg.mineral.bot.api.configuration.BotConfiguration;
import gg.mineral.bot.ai.goal.practice.PracticeAI;
import com.google.common.collect.ArrayListMultimap;
import java.net.Proxy;
import java.util.Random;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.util.IChatComponent;

public class VelocityBotManager {

    private static final String BOT_NAME_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final Object plugin;
    private final ProxyServer server;
    private final Logger logger;

    private static final String SUB_CHANNEL_BOT_DUEL = "BotDuel";
    private static final String SUB_CHANNEL_BOT_DUEL_STARTED = "BotDuelStarted";
    private static final String SUB_CHANNEL_BOT_DISCONNECT = "BotDisconnect";
    private static final String SUB_CHANNEL_BOT_GUIDE = "BotGuide";

    // Track active bots: Bot UUID -> ClientInstance
    private final Map<UUID, ClientInstance> activeBots = new ConcurrentHashMap<>();

    private void handleBotGuide(ByteArrayDataInput in) {
        try {
            // Read UUIDs as longs (Most Significant Bits, Least Significant Bits)
            long botUuidMost = in.readLong();
            long botUuidLeast = in.readLong();
            long targetUuidMost = in.readLong();
            long targetUuidLeast = in.readLong();

            UUID botUuid = new UUID(botUuidMost, botUuidLeast);
            UUID targetUuid = new UUID(targetUuidMost, targetUuidLeast);

            // Bot Data
            double bX = in.readDouble();
            double bY = in.readDouble();
            double bZ = in.readDouble();
            float bYaw = in.readFloat();
            float bPitch = in.readFloat();

            // Target Data
            double tX = in.readDouble();
            double tY = in.readDouble();
            double tZ = in.readDouble();
            float tYaw = in.readFloat();
            float tPitch = in.readFloat();

            double tVelX = in.readDouble();
            double tVelY = in.readDouble();
            double tVelZ = in.readDouble();

            double botHealth = in.readDouble();
            double targetHealth = in.readDouble();
            int botFood = in.readInt();
            float botSat = in.readFloat();
            boolean targetBlocking = in.readBoolean();

            // Update ClientInstance directly
            ClientInstance bot = getBot(botUuid);
            if (bot != null) {
                bot.updateFromGuide(
                        bX, bY, bZ, bYaw, bPitch, (float) botHealth, botFood, botSat,
                        targetUuid, tX, tY, tZ, tYaw, tPitch,
                        tVelX, tVelY, tVelZ, (float) targetHealth, targetBlocking);
            }

        } catch (Exception e) {
            // Suppress error log frequency in production if needed, but for now keep it
            logger.error("Failed to parse BotGuide message", e);
        }
    }

    // Track bot targets: Bot UUID -> Target Player UUID
    private final Map<UUID, UUID> botTargets = new ConcurrentHashMap<>();

    // Track kit types: Bot UUID -> Kit Type
    private final Map<UUID, String> kitTypes = new ConcurrentHashMap<>();

    // Track bot by username: Username -> Bot UUID (for matching when server sends
    // different UUID)
    private final Map<String, UUID> botsByUsername = new ConcurrentHashMap<>();

    // Track bot tasks: Bot UUID -> ScheduledTask
    private final Map<UUID, com.velocitypowered.api.scheduler.ScheduledTask> botTasks = new ConcurrentHashMap<>();

    // Prevent repeated scheduler ticks from re-entering the same bot loop concurrently.
    private final Map<UUID, AtomicBoolean> botLoopGuards = new ConcurrentHashMap<>();

    // Track server-assigned UUID to our UUID: Server UUID -> Our Bot UUID
    private final Map<UUID, UUID> serverUuidToOurUuid = new ConcurrentHashMap<>();

    public VelocityBotManager(Object plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;

        logger.info("VelocityBotManager initialized (Guide Dog mode)");
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

    private UUID findCandidateBotUuid(UUID playerUUID, String kitType) {
        for (Map.Entry<UUID, ClientInstance> entry : activeBots.entrySet()) {
            UUID candidateUuid = entry.getKey();
            ClientInstance candidate = entry.getValue();
            if (!isBotUsable(candidate)) {
                continue;
            }

            UUID targetUuid = botTargets.get(candidateUuid);
            String candidateKit = kitTypes.get(candidateUuid);
            if (playerUUID.equals(targetUuid) && candidateKit != null && candidateKit.equalsIgnoreCase(kitType)) {
                return candidateUuid;
            }
        }
        return null;
    }

    private UUID findUsableBotByKit(String kitType) {
        for (Map.Entry<UUID, String> entry : kitTypes.entrySet()) {
            UUID candidateUuid = entry.getKey();
            ClientInstance candidate = activeBots.get(candidateUuid);
            if (candidate != null && isBotUsable(candidate) && entry.getValue().equalsIgnoreCase(kitType)) {
                return candidateUuid;
            }
        }
        return null;
    }

    private boolean isBotUsable(ClientInstance bot) {
        return bot != null && bot.isRunning() && !(bot.getCurrentScreen() instanceof GuiDisconnected);
    }

    private String findUsername(UUID ourBotUUID) {
        for (Map.Entry<String, UUID> entry : botsByUsername.entrySet()) {
            if (entry.getValue().equals(ourBotUUID)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String getDisconnectText(ClientInstance bot) {
        Object currentScreen = bot.getCurrentScreen();
        if (!(currentScreen instanceof GuiDisconnected)) {
            return null;
        }

        IChatComponent reason = ((GuiDisconnected) currentScreen).getReason();
        return reason != null ? reason.getUnformattedText() : "Disconnected";
    }

    private boolean isTimeoutLike(String text) {
        return text != null && (text.contains("Timed out")
                || text.contains("ReadTimeoutException")
                || text.contains("disconnect.timeout"));
    }

    private boolean isFrequentConnectionKick(String text) {
        return text != null && (text.contains("\u4f60\u7684\u94fe\u63a5\u6b21\u6570\u8fc7\u4e8e\u9891\u7e41")
                || text.contains("\u7a0d\u540e\u518d\u8bd5"));
    }

    private void cleanupBot(UUID ourBotUUID, UUID serverBotUUID, boolean shutdownInstance) {
        ClientInstance bot = activeBots.remove(ourBotUUID);
        botTargets.remove(ourBotUUID);
        kitTypes.remove(ourBotUUID);

        if (serverBotUUID != null) {
            serverUuidToOurUuid.remove(serverBotUUID);
        }
        serverUuidToOurUuid.entrySet().removeIf(entry -> entry.getValue().equals(ourBotUUID));

        com.velocitypowered.api.scheduler.ScheduledTask task = botTasks.remove(ourBotUUID);
        if (task != null) {
            task.cancel();
        }

        botLoopGuards.remove(ourBotUUID);

        String username = findUsername(ourBotUUID);
        if (username != null) {
            botsByUsername.remove(username);
        }

        if (!shutdownInstance || bot == null) {
            return;
        }

        try {
            File runDir = bot.mcDataDir;
            bot.shutdown();

            if (runDir != null && runDir.exists()) {
                try {
                    org.apache.commons.io.FileUtils.deleteDirectory(runDir);
                    logger.info("Deleted bot run directory: {}", runDir.getAbsolutePath());
                } catch (Exception e) {
                    logger.warn("Failed to delete bot run directory: {}", runDir.getAbsolutePath(), e);
                }
            }
        } catch (Exception e) {
            logger.error("Error shutting down bot {}", ourBotUUID, e);
        }
    }

    private boolean handleDisconnectedBot(
            UUID ourBotUUID,
            ClientInstance bot,
            UUID playerUUID,
            String serverName,
            String kitType,
            int retryCount,
            String botUsername
    ) {
        String disconnectText = getDisconnectText(bot);
        if (disconnectText == null) {
            return false;
        }

        logger.warn("Bot {} disconnected: {}", botUsername, disconnectText);
        boolean frequentKick = isFrequentConnectionKick(disconnectText);
        boolean timeoutLike = isTimeoutLike(disconnectText);

        cleanupBot(ourBotUUID, null, true);

        if (frequentKick && retryCount < 3) {
            logger.warn("Bot {} was kicked for frequent connection. Retrying (#{})", botUsername, retryCount + 1);
            server.getScheduler().buildTask(plugin, () ->
                    createAndConnectBot(playerUUID, serverName, kitType, retryCount + 1)
            ).delay(1, TimeUnit.SECONDS).schedule();
        } else if (timeoutLike && retryCount < 3) {
            logger.warn("Bot {} hit read timeout, recreating (retry #{})", botUsername, retryCount + 1);
            server.getScheduler().buildTask(plugin, () ->
                    createAndConnectBot(playerUUID, serverName, kitType, retryCount + 1)
            ).delay(1200, TimeUnit.MILLISECONDS).schedule();
        }

        return true;
    }
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        ChannelIdentifier identifier = event.getIdentifier();
        // logger.info("DEBUG: PluginMessageEvent received. ID: '{}' (Class: {})",
        // identifier.getId(),
        // identifier.getClass().getName());

        // Check for MineralBot channel
        boolean isMineralBot = identifier.getId().equals("MineralBot") || identifier.getId().equals("mineralbot:main");

        if (!isMineralBot) {
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String subChannel;
        try {
            subChannel = in.readUTF();
            // logger.info("Plugin Message received on channel: {} with subchannel: {}",
            // identifier.getId(), subChannel);
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
            case SUB_CHANNEL_BOT_GUIDE:
                handleBotGuide(in);
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

            createAndConnectBot(playerUUID, serverName, kitType, 0);
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

            ClientInstance bot = activeBots.get(serverBotUUID);
            UUID ourBotUUID = serverBotUUID;

            if (!isBotUsable(bot)) {
                logger.info("Bot not found by server UUID, searching by player UUID and kit type...");
                UUID candidateUuid = findCandidateBotUuid(playerUUID, kitType);
                if (candidateUuid == null) {
                    logger.info("No exact player/kit match found, falling back to kit type...");
                    candidateUuid = findUsableBotByKit(kitType);
                }

                if (candidateUuid != null) {
                    ourBotUUID = candidateUuid;
                    bot = activeBots.get(ourBotUUID);
                    serverUuidToOurUuid.put(serverBotUUID, ourBotUUID);
                    logger.info("Matched bot: ourUUID={}, serverUUID={}", ourBotUUID, serverBotUUID);
                }
            }

            if (!isBotUsable(bot)) {
                logger.warn("Could not find active bot for BotDuelStarted. Server UUID: {}", serverBotUUID);
                logger.warn("Active bots: {}", activeBots.keySet());
                logger.warn("Kit types: {}", kitTypes);
                return;
            }

            botTargets.put(ourBotUUID, playerUUID);
            kitTypes.put(ourBotUUID, kitType);

            logger.info("Configuring combat AI for bot {} with kit type: {}", ourBotUUID, kitType);
            PracticeAI.INSTANCE.configureBotForKit(bot, kitType);
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

            UUID ourBotUUID = resolveToOurUuid(serverBotUUID);
            logger.info("Resolved to our UUID: {}", ourBotUUID);

            cleanupBot(ourBotUUID, serverBotUUID, true);

            server.getPlayer(serverBotUUID).ifPresent(player -> {
                player.disconnect(net.kyori.adventure.text.Component.text("§eMatch ended. GG!"));
            });
        } catch (Exception e) {
            logger.error("Failed to parse BotDisconnect message", e);
        }
    }
    private void createAndConnectBot(UUID playerUUID, String serverName, String kitType, int retryCount) {
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

                String botUsername = generateUniqueBotUsername(kitType);
                if (retryCount > 0) {
                    logger.info("Retrying with new bot name: {} (Retry #{})", botUsername, retryCount);
                }

                config.setUuid(botUUID);
                config.setUsername(botUsername);
                config.setDebug(false);

                // ===== Bot CPS tuning =====
                // If you want to change attack speed, modify these two values:
                // averageCps: base clicks-per-second
                // cpsDeviation: random fluctuation range around averageCps
                config.setAverageCps(10.0f);
                config.setCpsDeviation(1.0f);

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
                botsByUsername.put(config.getUsername(), botUUID);
                botLoopGuards.put(botUUID, new AtomicBoolean(false));

                // Initialize
                logger.info("Starting bot instance for {} (UUID: {})", config.getUsername(), botUUID);

                bot.run();

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
                final String finalBotUsername = config.getUsername();
                final AtomicBoolean loopGuard = botLoopGuards.get(botUUID);
                com.velocitypowered.api.scheduler.ScheduledTask loopTask = server.getScheduler()
                        .buildTask(plugin, () -> {
                            if (loopGuard == null || !loopGuard.compareAndSet(false, true)) {
                                return;
                            }

                            try {
                                if (!bot.isRunning()) {
                                    cleanupBot(botUUID, null, false);
                                    logger.info("Bot task self-cancelled for {}", botUUID);
                                    return;
                                }

                                if (handleDisconnectedBot(botUUID, bot, playerUUID, serverName, kitType, retryCount,
                                        finalBotUsername)) {
                                    return;
                                }

                                try {
                                    bot.runGameLoop();
                                } catch (Exception e) {
                                    logger.error("Error in bot game loop", e);

                                    String message = e.toString();
                                    boolean timeoutLike = message != null && message.contains("ReadTimeoutException");
                                    if (!timeoutLike && e.getCause() != null) {
                                        String causeMsg = e.getCause().toString();
                                        timeoutLike = causeMsg != null && causeMsg.contains("ReadTimeoutException");
                                    }

                                    boolean chatCrash = false;
                                    for (StackTraceElement element : e.getStackTrace()) {
                                        if ("net.minecraft.client.gui.GuiNewChat".equals(element.getClassName())) {
                                            chatCrash = true;
                                            break;
                                        }
                                    }
                                    if (!chatCrash && e.getCause() != null) {
                                        for (StackTraceElement element : e.getCause().getStackTrace()) {
                                            if ("net.minecraft.client.gui.GuiNewChat".equals(element.getClassName())) {
                                                chatCrash = true;
                                                break;
                                            }
                                        }
                                    }

                                    if (timeoutLike || chatCrash) {
                                        cleanupBot(botUUID, null, true);
                                        if (retryCount < 3) {
                                            logger.warn("Bot {} hit {}, recreating (retry #{})",
                                                    finalBotUsername,
                                                    timeoutLike ? "read timeout" : "chat crash",
                                                    retryCount + 1);
                                            server.getScheduler().buildTask(plugin, () ->
                                                    createAndConnectBot(playerUUID, serverName, kitType, retryCount + 1)
                                            ).delay(1200, TimeUnit.MILLISECONDS).schedule();
                                        }
                                        return;
                                    }
                                }

                                handleDisconnectedBot(botUUID, bot, playerUUID, serverName, kitType, retryCount,
                                        finalBotUsername);
                            } finally {
                                loopGuard.set(false);
                            }
                        }).repeat(50, TimeUnit.MILLISECONDS).schedule();
                botTasks.put(botUUID, loopTask);

            } catch (Exception e) {
                logger.error("Failed to start bot", e);
            }
        }).schedule();
    }

    private String generateUniqueBotUsername(String kitType) {
        String username;
        do {
            username = kitType + generateRandomSuffix(3);
        } while (botsByUsername.containsKey(username));
        return username;
    }

    private String generateRandomSuffix(int length) {
        StringBuilder sb = new StringBuilder(length);
        Random random = new Random();
        for (int i = 0; i < length; i++) {
            sb.append(BOT_NAME_CHARS.charAt(random.nextInt(BOT_NAME_CHARS.length())));
        }
        return sb.toString();
    }

    @Subscribe
    public void onPreLogin(com.velocitypowered.api.event.connection.PreLoginEvent event) {
        String username = event.getUsername();
        // Check if this is one of our bots
        if (botsByUsername.containsKey(username)) {
            logger.info("Bypassing authentication for internal bot: {}", username);
            event.setResult(
                    com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }
}
