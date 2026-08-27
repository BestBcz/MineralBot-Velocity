package com.mineralstudios.bot.velocity;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import org.slf4j.Logger;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.File;

import gg.mineral.bot.base.client.instance.ClientInstance;
import gg.mineral.bot.base.client.gui.GuiConnecting;
import gg.mineral.bot.api.entity.ClientEntity;
import gg.mineral.bot.api.configuration.BotDifficulty;
import gg.mineral.bot.api.configuration.BotConfiguration;
import gg.mineral.bot.api.entity.living.player.ClientPlayer;
import gg.mineral.bot.ai.goal.practice.PracticeAI;
import com.google.common.collect.ArrayListMultimap;
import java.net.Proxy;
import java.util.Locale;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

public class VelocityBotManager {

    private final Object plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final boolean guideEnabled;
    private final String botConnectHost;
    private final int botConnectPort;

    private static final String SUB_CHANNEL_BOT_DUEL = "BotDuel";
    private static final String SUB_CHANNEL_BOT_DUEL_STARTED = "BotDuelStarted";
    private static final String SUB_CHANNEL_BOT_DISCONNECT = "BotDisconnect";
    private static final String SUB_CHANNEL_BOT_GUIDE = "BotGuide";
    private static final String SUB_CHANNEL_BOT_DUEL_FAILED = "BotDuelFailed";
    private static final String SUB_CHANNEL_BOT_REPAIR_MATCH_ENTITIES = "BotRepairMatchEntities";
    private static final long BOT_RECREATE_DELAY_MILLIS = 1_200L;
    private static final long BOT_LOOP_STALL_MILLIS = 5_000L;

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
            UUID ourBotUuid = resolveToOurUuid(botUuid);
            ClientInstance bot = getBot(ourBotUuid);
            if (bot != null) {
                botTargets.put(ourBotUuid, targetUuid);
                bot.setGuidedTargetUuid(targetUuid);
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

    // Track difficulties: Bot UUID -> Bot Difficulty
    private final Map<UUID, BotDifficulty> botDifficulties = new ConcurrentHashMap<>();

    // Track request tokens: Bot UUID -> Pending request token
    private final Map<UUID, String> botRequestTokens = new ConcurrentHashMap<>();

    // Track bot by username: Username -> Bot UUID (for matching when server sends
    // different UUID)
    private final Map<String, UUID> botsByUsername = new ConcurrentHashMap<>();

    // Track bot tasks: Bot UUID -> ScheduledTask
    private final Map<UUID, com.velocitypowered.api.scheduler.ScheduledTask> botTasks = new ConcurrentHashMap<>();

    // Prevent repeated scheduler ticks from re-entering the same bot loop concurrently.
    private final Map<UUID, AtomicBoolean> botLoopGuards = new ConcurrentHashMap<>();
    private final Map<UUID, Long> botLoopStartedAtMillis = new ConcurrentHashMap<>();

    // Track server-assigned UUID to our UUID: Server UUID -> Our Bot UUID
    private final Map<UUID, UUID> serverUuidToOurUuid = new ConcurrentHashMap<>();

    // Friend UUIDs sent by the practice server, keyed by our internal bot UUID.
    private final Map<UUID, Set<UUID>> botDeclaredFriendlyUuids = new ConcurrentHashMap<>();

    // Track diagnostics: Bot UUID -> diagnostics snapshot/state
    private final Map<UUID, BotSessionDiagnostics> botDiagnostics = new ConcurrentHashMap<>();

    public VelocityBotManager(
            Object plugin,
            ProxyServer server,
            Logger logger,
            boolean guideEnabled,
            String botConnectHost,
            int botConnectPort
    ) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.guideEnabled = guideEnabled;
        this.botConnectHost = botConnectHost;
        this.botConnectPort = botConnectPort;

        logger.info(
                "VelocityBotManager initialized (guide-enabled={}, bot-connect={}:{})",
                guideEnabled,
                botConnectHost,
                botConnectPort
        );
    }

    void recordProxyPacket(String botUsername, String packetKey) {
        if (botUsername == null || packetKey == null) {
            return;
        }

        UUID botUuid = botsByUsername.get(botUsername);
        if (botUuid == null) {
            return;
        }

        recordBotPacket(botUuid, packetKey, Integer.MIN_VALUE, null);
    }

    private void recordBotPacket(UUID botUuid, String packetKey, int entityId, UUID entityUuid) {
        BotSessionDiagnostics diagnostics = botDiagnostics.get(botUuid);
        if (diagnostics == null || packetKey == null) {
            return;
        }

        diagnostics.markPacket(packetKey, entityId, entityUuid);
    }

    private BotSessionDiagnostics getDiagnostics(UUID botUuid) {
        return botDiagnostics.get(botUuid);
    }

    private void sendPluginMessageToPlayerServer(UUID playerUuid, byte[] payload) {
        server.getPlayer(playerUuid)
                .flatMap(Player::getCurrentServer)
                .ifPresentOrElse(
                        connection -> connection.getServer()
                                .sendPluginMessage(MineralBotVelocity.MINERAL_BOT_CHANNEL, payload),
                        () -> logger.warn("Unable to send MineralBot plugin message. Player {} is not on a backend server.",
                                playerUuid)
                );
    }

    private void notifyBotDuelFailed(BotSessionDiagnostics diagnostics, String reason, String detail) {
        if (diagnostics == null || !diagnostics.markFailureReported()) {
            return;
        }

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(SUB_CHANNEL_BOT_DUEL_FAILED);
        out.writeUTF(diagnostics.getPlayerUuid().toString());
        out.writeUTF(diagnostics.getRequestToken() == null ? "" : diagnostics.getRequestToken());
        out.writeUTF(reason == null ? "unknown" : reason);
        out.writeUTF(detail == null ? "" : detail);
        sendPluginMessageToPlayerServer(diagnostics.getPlayerUuid(), out.toByteArray());
    }

    private void requestEntityRepair(BotSessionDiagnostics diagnostics, String reason) {
        if (diagnostics == null || !diagnostics.isDuelStarted()) {
            return;
        }

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(SUB_CHANNEL_BOT_REPAIR_MATCH_ENTITIES);
        out.writeUTF(diagnostics.getPlayerUuid().toString());
        out.writeUTF(diagnostics.getRequestToken() == null ? "" : diagnostics.getRequestToken());
        out.writeUTF(reason == null ? "unknown" : reason);
        sendPluginMessageToPlayerServer(diagnostics.getPlayerUuid(), out.toByteArray());
    }

    private void scheduleBotRecreate(
            UUID playerUUID,
            String serverName,
            String kitType,
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int nextRetryCount,
            long delayMillis
    ) {
        server.getScheduler().buildTask(plugin, () ->
                createAndConnectBot(playerUUID, serverName, kitType, difficulty, requestToken, latencyMillis,
                        nextRetryCount)
        ).delay(delayMillis, TimeUnit.MILLISECONDS).schedule();
    }

    private void handleBotLoopStall(
            UUID botUUID,
            UUID playerUUID,
            String serverName,
            String kitType,
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int retryCount,
            String botUsername
    ) {
        Long startedAt = botLoopStartedAtMillis.get(botUUID);
        long now = System.currentTimeMillis();
        if (startedAt == null || now - startedAt < BOT_LOOP_STALL_MILLIS) {
            return;
        }

        AtomicBoolean guard = botLoopGuards.remove(botUUID);
        if (guard == null) {
            return;
        }

        BotSessionDiagnostics diagnostics = getDiagnostics(botUUID);
        boolean duelStarted = diagnostics != null && diagnostics.isDuelStarted();
        String detail = "Bot game loop did not return for " + (now - startedAt) + "ms.";
        if (diagnostics != null) {
            diagnostics.noteDisconnect("game-loop-stall");
        }

        logger.error("Bot {} game loop stalled for {}ms. duelStarted={}",
                botUsername, now - startedAt, duelStarted);
        if (duelStarted && diagnostics != null) {
            logger.warn("Bot {} stalled after duel start; not recreating into lobby.", botUsername);
            notifyBotDuelFailed(diagnostics, "game-loop-stall", detail);
            cleanupBot(botUUID, null, true);
            return;
        }

        cleanupBot(botUUID, null, true);

        if (!duelStarted && retryCount < 3) {
            logger.warn("Bot {} stalled before duel start, recreating (retry #{})",
                    botUsername, retryCount + 1);
            scheduleBotRecreate(playerUUID, serverName, kitType, difficulty, requestToken, latencyMillis,
                    retryCount + 1, BOT_RECREATE_DELAY_MILLIS);
        } else if (diagnostics != null) {
            notifyBotDuelFailed(diagnostics, "game-loop-stall", detail);
        }
    }

    private boolean runStartupWatchdog(UUID botUuid) {
        BotSessionDiagnostics diagnostics = getDiagnostics(botUuid);
        if (diagnostics == null) {
            return false;
        }

        long now = System.currentTimeMillis();

        if (diagnostics.shouldWarnStartup(now)) {
            diagnostics.markStartupWarningLogged();
            logger.warn("Bot startup missing packets. {}", diagnostics.startupSummary());
        }

        if (!diagnostics.shouldFailStartup(now)) {
            return false;
        }

        logger.error("Bot startup timed out before duel start. {}", diagnostics.startupSummary());
        notifyBotDuelFailed(diagnostics, "startup-timeout",
                "Bot did not finish startup before timeout.");
        cleanupBot(botUuid, null, true);
        return true;
    }

    private void runTargetWatchdog(UUID botUuid, ClientInstance bot) {
        BotSessionDiagnostics diagnostics = getDiagnostics(botUuid);
        if (diagnostics == null || !diagnostics.isDuelStarted()) {
            return;
        }

        UUID expectedTargetUuid = botTargets.get(botUuid);
        if (expectedTargetUuid == null) {
            return;
        }

        diagnostics.setExpectedTargetUuid(expectedTargetUuid);

        ClientPlayer target = findTargetPlayer(bot, expectedTargetUuid);
        if (target != null) {
            diagnostics.noteTargetSeen(target.getEntityId());
            return;
        }

        diagnostics.noteTargetMissingTick();

        if (diagnostics.shouldLogTargetLoss()) {
            diagnostics.markTargetLossLogged();
            logger.warn("Bot lost its target entity. {}", diagnostics.targetSummary(false));
        }

        long now = System.currentTimeMillis();
        if (diagnostics.shouldRequestRepair(now)) {
            diagnostics.markRepairRequested(now);
            logger.warn("Requesting bot match entity repair. {}", diagnostics.targetSummary(false));
            requestEntityRepair(diagnostics, "missing-target");
        }
    }

    private ClientPlayer findTargetPlayer(ClientInstance bot, UUID targetUuid) {
        if (bot == null || targetUuid == null) {
            return null;
        }

        for (ClientEntity entity : bot.getFakePlayer().getWorld().getEntities()) {
            if (targetUuid.equals(entity.getUuid()) && entity instanceof ClientPlayer player) {
                return player;
            }
        }

        return null;
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
     * Expands practice-server friend UUIDs to every identity an entity can use
     * in the bot client: backend UUID, internal bot UUID and offline-mode UUID.
     * This is rerun whenever another bot finishes its UUID mapping because the
     * first BotDuelStarted packet can arrive before later teammates are mapped.
     */
    private void refreshAllFriendlyUuidMappings() {
        for (Map.Entry<UUID, Set<UUID>> entry : botDeclaredFriendlyUuids.entrySet()) {
            UUID ourBotUuid = entry.getKey();
            ClientInstance bot = activeBots.get(ourBotUuid);
            if (!isBotUsable(bot)) {
                continue;
            }

            Set<UUID> resolvedFriendlies = new HashSet<>();
            for (UUID declaredUuid : entry.getValue()) {
                resolvedFriendlies.add(declaredUuid);

                UUID friendlyInternalUuid = resolveToOurUuid(declaredUuid);
                resolvedFriendlies.add(friendlyInternalUuid);

                ClientInstance friendlyBot = activeBots.get(friendlyInternalUuid);
                if (friendlyBot == null) {
                    continue;
                }

                BotConfiguration friendlyConfiguration = friendlyBot.getConfiguration();
                resolvedFriendlies.add(friendlyConfiguration.getUuid());

                String friendlyUsername = friendlyConfiguration.getFullUsername();
                if (friendlyUsername != null && !friendlyUsername.isEmpty()) {
                    resolvedFriendlies.add(UUID.nameUUIDFromBytes(
                            ("OfflinePlayer:" + friendlyUsername).getBytes(StandardCharsets.UTF_8)));
                }
            }

            resolvedFriendlies.remove(ourBotUuid);
            resolvedFriendlies.remove(bot.getConfiguration().getUuid());

            bot.getConfiguration().getFriendlyUUIDs().clear();
            bot.getConfiguration().getFriendlyUUIDs().addAll(resolvedFriendlies);
            logger.info("Refreshed friendly UUID aliases for bot {}: {}", ourBotUuid, resolvedFriendlies);
        }
    }

    private UUID findCandidateBotUuid(UUID playerUUID, String kitType, BotDifficulty difficulty, String requestToken) {
        for (Map.Entry<UUID, ClientInstance> entry : activeBots.entrySet()) {
            UUID candidateUuid = entry.getKey();
            ClientInstance candidate = entry.getValue();
            if (!isBotUsable(candidate)) {
                continue;
            }

            UUID targetUuid = botTargets.get(candidateUuid);
            String candidateKit = kitTypes.get(candidateUuid);
            BotDifficulty candidateDifficulty = botDifficulties.get(candidateUuid);
            String candidateToken = botRequestTokens.get(candidateUuid);
            if (!playerUUID.equals(targetUuid)) {
                continue;
            }

            if (requestToken != null && requestToken.equalsIgnoreCase(candidateToken)) {
                return candidateUuid;
            }

            if (candidateKit != null
                    && candidateKit.equalsIgnoreCase(kitType)
                    && candidateDifficulty == difficulty) {
                return candidateUuid;
            }
        }
        return null;
    }

    private UUID findUsableBotByKit(String kitType, BotDifficulty difficulty) {
        for (Map.Entry<UUID, String> entry : kitTypes.entrySet()) {
            UUID candidateUuid = entry.getKey();
            ClientInstance candidate = activeBots.get(candidateUuid);
            BotDifficulty candidateDifficulty = botDifficulties.get(candidateUuid);
            if (candidate != null
                    && isBotUsable(candidate)
                    && entry.getValue().equalsIgnoreCase(kitType)
                    && candidateDifficulty == difficulty) {
                return candidateUuid;
            }
        }
        return null;
    }

    private boolean isBotUsable(ClientInstance bot) {
        return bot != null && bot.isRunning() && getDisconnectText(bot) == null;
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
        if (bot == null) {
            return "Bot instance is null";
        }

        Object currentScreen = bot.getCurrentScreen();
        if (currentScreen instanceof GuiDisconnected) {
            IChatComponent reason = ((GuiDisconnected) currentScreen).getReason();
            return reason != null ? reason.getUnformattedText() : "Disconnected";
        }

        NetworkManager networkManager = getNetworkManager(bot);
        if (networkManager == null || networkManager.isChannelOpen()) {
            return null;
        }

        IChatComponent reason = networkManager.getExitMessage();
        return reason != null ? reason.getUnformattedText() : "Disconnected from server";
    }

    private NetworkManager getNetworkManager(ClientInstance bot) {
        if (bot == null) {
            return null;
        }

        NetHandlerPlayClient playHandler = bot.getNetHandler();
        if (playHandler != null) {
            return playHandler.getNetworkManager();
        }

        Object currentScreen = bot.getCurrentScreen();
        if (currentScreen instanceof GuiConnecting connecting) {
            return connecting.getNetworkManager();
        }

        return null;
    }

    private void closeBotNetwork(ClientInstance bot, String reason) {
        NetworkManager networkManager = getNetworkManager(bot);
        if (networkManager == null) {
            return;
        }

        try {
            networkManager.closeChannel(new ChatComponentText(reason == null ? "MineralBot cleanup" : reason));
        } catch (Exception e) {
            logger.warn("Failed to close bot network channel during cleanup", e);
        }
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
        botDifficulties.remove(ourBotUUID);
        botRequestTokens.remove(ourBotUUID);
        botDiagnostics.remove(ourBotUUID);
        botDeclaredFriendlyUuids.remove(ourBotUUID);

        if (serverBotUUID != null) {
            serverUuidToOurUuid.remove(serverBotUUID);
        }
        serverUuidToOurUuid.entrySet().removeIf(entry -> entry.getValue().equals(ourBotUUID));
        refreshAllFriendlyUuidMappings();

        com.velocitypowered.api.scheduler.ScheduledTask task = botTasks.remove(ourBotUUID);
        if (task != null) {
            task.cancel();
        }

        botLoopGuards.remove(ourBotUUID);
        botLoopStartedAtMillis.remove(ourBotUUID);

        String username = findUsername(ourBotUUID);
        if (username != null) {
            botsByUsername.remove(username);
        }

        if (!shutdownInstance || bot == null) {
            return;
        }

        try {
            File runDir = bot.mcDataDir;
            bot.setPacketDiagnosticsListener(null);
            closeBotNetwork(bot, "MineralBot cleanup");
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
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int retryCount,
            String botUsername
    ) {
        String disconnectText = getDisconnectText(bot);
        if (disconnectText == null) {
            return false;
        }

        BotSessionDiagnostics diagnostics = getDiagnostics(ourBotUUID);
        if (diagnostics != null) {
            diagnostics.noteDisconnect(disconnectText);
        }
        boolean duelStarted = diagnostics != null && diagnostics.isDuelStarted();

        logger.warn("Bot {} disconnected: {}", botUsername, disconnectText);
        boolean frequentKick = isFrequentConnectionKick(disconnectText);
        boolean timeoutLike = isTimeoutLike(disconnectText);

        cleanupBot(ourBotUUID, null, true);

        if (!duelStarted && frequentKick && retryCount < 3) {
            logger.warn("Bot {} was kicked for frequent connection. Retrying (#{})", botUsername, retryCount + 1);
            scheduleBotRecreate(playerUUID, serverName, kitType, difficulty, requestToken, latencyMillis,
                    retryCount + 1, 1_000L);
        } else if (!duelStarted && timeoutLike && retryCount < 3) {
            logger.warn("Bot {} hit read timeout, recreating (retry #{})", botUsername, retryCount + 1);
            scheduleBotRecreate(playerUUID, serverName, kitType, difficulty, requestToken, latencyMillis,
                    retryCount + 1, BOT_RECREATE_DELAY_MILLIS);
        } else if (diagnostics != null) {
            String reason = frequentKick ? "frequent-connection-kick" : timeoutLike ? "read-timeout" : "bot-disconnected";
            if (duelStarted) {
                logger.warn("Bot {} disconnected after duel start; not recreating into lobby. reason={}",
                        botUsername, reason);
            }
            notifyBotDuelFailed(diagnostics, reason, disconnectText);
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
                if (guideEnabled) {
                    handleBotGuide(in);
                }
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
            BotDifficulty difficulty = BotDifficulty.fromId(in.readUTF());
            String requestToken = in.readUTF();
            int latencyMillis;
            try {
                latencyMillis = Math.max(0, in.readInt());
            } catch (IllegalStateException ignored) {
                // Keep requests from older practice-server builds compatible.
                latencyMillis = 0;
            }

            UUID playerUUID = UUID.fromString(playerUUIDStr);

            logger.info("Received BotDuel request: Player={}, Server={}, Kit={}, Difficulty={}, Latency={}ms, Token={}",
                    playerUUID, serverName, kitType, difficulty.getId(), latencyMillis, requestToken);

            createAndConnectBot(playerUUID, serverName, kitType, difficulty, requestToken, latencyMillis, 0);
        } catch (Exception e) {
            logger.error("Failed to parse BotDuel message", e);
        }
    }

    private void handleBotDuelStarted(ByteArrayDataInput in) {
        try {
            String playerUUIDStr = in.readUTF();
            String botUUIDStr = in.readUTF();
            String kitType = in.readUTF();
            BotDifficulty difficulty = BotDifficulty.fromId(in.readUTF());
            String requestToken = in.readUTF();

            UUID playerUUID = UUID.fromString(playerUUIDStr);
            UUID serverBotUUID = UUID.fromString(botUUIDStr);
            UUID targetUUID = playerUUID;
            Set<UUID> friendlyUUIDs = new HashSet<>();

            try {
                targetUUID = UUID.fromString(in.readUTF());
                int friendlyCount = Math.max(0, in.readInt());
                for (int index = 0; index < friendlyCount; index++) {
                    friendlyUUIDs.add(UUID.fromString(in.readUTF()));
                }
            } catch (IllegalStateException ignored) {
                // Backwards compatibility with practice builds that only sent a 1v1 target.
            }

            logger.info("BotDuel started: Owner={}, Target={}, Bot(server)={}, Kit={}, Difficulty={}, Token={}, Friendlies={}",
                    playerUUID, targetUUID, serverBotUUID, kitType, difficulty.getId(), requestToken, friendlyUUIDs);

            ClientInstance bot = activeBots.get(serverBotUUID);
            UUID ourBotUUID = serverBotUUID;

            if (!isBotUsable(bot)) {
                logger.info("Bot not found by server UUID, searching by player UUID, difficulty, and request token...");
                UUID candidateUuid = findCandidateBotUuid(playerUUID, kitType, difficulty, requestToken);
                if (candidateUuid == null) {
                    logger.info("No exact player/token match found, falling back to kit and difficulty...");
                    candidateUuid = findUsableBotByKit(kitType, difficulty);
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

            // Keep this mapping even if both UUIDs currently match. Each new
            // mapping may complete an earlier teammate's friendly alias set.
            serverUuidToOurUuid.put(serverBotUUID, ourBotUUID);

            BotSessionDiagnostics diagnostics = getDiagnostics(ourBotUUID);
            if (diagnostics != null) {
                diagnostics.markDuelStarted(playerUUID);
            }

            botTargets.put(ourBotUUID, targetUUID);
            bot.setGuidedTargetUuid(targetUUID);
            kitTypes.put(ourBotUUID, kitType);
            botDifficulties.put(ourBotUUID, difficulty);
            botRequestTokens.put(ourBotUUID, requestToken);
            botDeclaredFriendlyUuids.put(ourBotUUID, new HashSet<>(friendlyUUIDs));
            refreshAllFriendlyUuidMappings();

            logger.info("Configuring combat AI for bot {} with kit type {} at difficulty {}", ourBotUUID, kitType,
                    difficulty.getId());
            PracticeAI.INSTANCE.configureBotForKit(bot, kitType, difficulty);
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
    private void createAndConnectBot(
            UUID playerUUID,
            String serverName,
            String kitType,
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int retryCount
    ) {
        server.getScheduler().buildTask(plugin, () -> {
            UUID botUUID = UUID.randomUUID();
            String botUsername = generateUniqueBotUsername(kitType, requestToken);
            BotSessionDiagnostics diagnostics =
                    new BotSessionDiagnostics(playerUUID, botUUID, botUsername, requestToken, retryCount);

            try {
                // Get Server Info
                var serverInfo = server.getServer(serverName).orElse(null);
                if (serverInfo == null) {
                    logger.error("Server {} not found", serverName);
                    notifyBotDuelFailed(diagnostics, "server-not-found",
                            "Backend server " + serverName + " is not registered on the proxy.");
                    return;
                }

                // Configure Bot
                BotConfiguration config = new BotConfiguration();

                if (retryCount > 0) {
                    logger.info("Retrying with new bot name: {} (Retry #{})", botUsername, retryCount);
                }

                config.setUuid(botUUID);
                config.setUsername(botUsername);
                config.setDebug(false);
                difficulty.applyTo(config);
                config.setLatency(latencyMillis);

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

                bot.setPacketDiagnosticsListener((packetKey, entityId, entityUuid) ->
                        recordBotPacket(botUUID, packetKey, entityId, entityUuid));

                // Set Connection Info - connect to proxy
                bot.setServer(botConnectHost, botConnectPort);

                // Track the bot and kit type
                activeBots.put(botUUID, bot);
                botTargets.put(botUUID, playerUUID);
                kitTypes.put(botUUID, kitType);
                botDifficulties.put(botUUID, difficulty);
                botRequestTokens.put(botUUID, requestToken);
                botDiagnostics.put(botUUID, diagnostics);
                botsByUsername.put(config.getUsername(), botUUID);
                botLoopGuards.put(botUUID, new AtomicBoolean(false));

                // Initialize
                logger.info("Starting bot instance for {} (UUID: {}, Difficulty: {}, Latency: {}ms, Token: {})",
                        config.getUsername(), botUUID, difficulty.getId(), latencyMillis, requestToken);

                bot.run();

                // Schedule Game Loop
                final String finalBotUsername = config.getUsername();
                final AtomicBoolean loopGuard = botLoopGuards.get(botUUID);
                com.velocitypowered.api.scheduler.ScheduledTask loopTask = server.getScheduler()
                        .buildTask(plugin, () -> {
                            if (loopGuard == null) {
                                return;
                            }

                            if (!loopGuard.compareAndSet(false, true)) {
                                handleBotLoopStall(botUUID, playerUUID, serverName, kitType, difficulty,
                                        requestToken, latencyMillis, retryCount, finalBotUsername);
                                return;
                            }
                            botLoopStartedAtMillis.put(botUUID, System.currentTimeMillis());

                            try {
                                if (!bot.isRunning()) {
                                    cleanupBot(botUUID, null, false);
                                    logger.info("Bot task self-cancelled for {}", botUUID);
                                    return;
                                }

                                if (handleDisconnectedBot(botUUID, bot, playerUUID, serverName, kitType, difficulty,
                                        requestToken, latencyMillis, retryCount, finalBotUsername)) {
                                    return;
                                }

                                if (runStartupWatchdog(botUUID)) {
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
                                        BotSessionDiagnostics currentDiagnostics = getDiagnostics(botUUID);
                                        if (currentDiagnostics != null) {
                                            currentDiagnostics.noteDisconnect(e.toString());
                                        }
                                        boolean duelStarted = currentDiagnostics != null
                                                && currentDiagnostics.isDuelStarted();
                                        if (duelStarted && currentDiagnostics != null) {
                                            logger.warn("Bot {} hit {} after duel start; not recreating into lobby.",
                                                    finalBotUsername,
                                                    timeoutLike ? "read timeout" : "chat crash");
                                            notifyBotDuelFailed(currentDiagnostics,
                                                    timeoutLike ? "read-timeout" : "chat-crash",
                                                    e.toString());
                                            cleanupBot(botUUID, null, true);
                                            return;
                                        }

                                        cleanupBot(botUUID, null, true);
                                        if (!duelStarted && retryCount < 3) {
                                            logger.warn("Bot {} hit {}, recreating (retry #{})",
                                                    finalBotUsername,
                                                    timeoutLike ? "read timeout" : "chat crash",
                                                    retryCount + 1);
                                            scheduleBotRecreate(playerUUID, serverName, kitType, difficulty,
                                                    requestToken, latencyMillis, retryCount + 1,
                                                    BOT_RECREATE_DELAY_MILLIS);
                                        } else if (currentDiagnostics != null) {
                                            notifyBotDuelFailed(currentDiagnostics,
                                                    timeoutLike ? "read-timeout" : "chat-crash",
                                                    e.toString());
                                        }
                                        return;
                                    }
                                }

                                if (handleDisconnectedBot(botUUID, bot, playerUUID, serverName, kitType, difficulty,
                                        requestToken, latencyMillis, retryCount, finalBotUsername)) {
                                    return;
                                }

                                runTargetWatchdog(botUUID, bot);
                            } finally {
                                botLoopStartedAtMillis.remove(botUUID);
                                loopGuard.set(false);
                            }
                        }).repeat(50, TimeUnit.MILLISECONDS).schedule();
                botTasks.put(botUUID, loopTask);

            } catch (Exception e) {
                logger.error("Failed to start bot", e);
                notifyBotDuelFailed(diagnostics, "create-failed", e.toString());
                cleanupBot(botUUID, null, true);
            }
        }).schedule();
    }

    private String generateUniqueBotUsername(String kitType, String requestToken) {
        String sanitizedKit = kitType == null ? "Bot" : kitType.replaceAll("[^A-Za-z0-9]", "");
        if (sanitizedKit.isEmpty()) {
            sanitizedKit = "Bot";
        }

        String token = requestToken == null ? "BOT0" : requestToken.toUpperCase(Locale.ROOT);
        int maxPrefixLength = Math.max(1, 16 - token.length());
        String prefix = sanitizedKit.length() > maxPrefixLength
                ? sanitizedKit.substring(0, maxPrefixLength)
                : sanitizedKit;
        return prefix + token;
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
