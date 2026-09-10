package com.mineralstudios.bot.velocity;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import org.slf4j.Logger;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
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
    private record DirectTarget(String name, java.net.InetSocketAddress address, com.velocitypowered.api.proxy.server.RegisteredServer server) { }

    static boolean isPermanentLoginFailure(String reason) {
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.contains("unable to authenticate") || lower.contains("bungeeguard")
                || lower.contains("ip forwarding") || lower.contains("forwarding_")
                || lower.contains("invalid token") || lower.contains("invalid session")
                || lower.contains("outdated") || lower.contains("encoderexception")
                || lower.contains("uuid_mismatch") || lower.contains("illegalargumentexception")
                || lower.contains("unknown host") || lower.contains("no pending bot match request");
    }

    private final Object plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final boolean guideEnabled;
    private final gg.mineral.bot.base.client.instance.BungeeGuardForwarding forwarding;
    private final boolean timingDiagnosticsEnabled;
    private final boolean velocityInputRecoveryEnabled;
    private final BotLoopScheduler loopScheduler;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    private static final String SUB_CHANNEL_BOT_DUEL = "BotDuel";
    private static final String SUB_CHANNEL_BOT_DUEL_STARTED = "BotDuelStarted";
    private static final String SUB_CHANNEL_BOT_DISCONNECT = "BotDisconnect";
    private static final String SUB_CHANNEL_BOT_GUIDE = "BotGuide";
    private static final String SUB_CHANNEL_BOT_DUEL_FAILED = "BotDuelFailed";
    private static final String SUB_CHANNEL_BOT_REPAIR_MATCH_ENTITIES = "BotRepairMatchEntities";
    private static final String SUB_CHANNEL_BOT_REQUEST_ACCEPTED = "BotRequestAccepted";
    private static final String SUB_CHANNEL_BOT_REQUEST_CANCELLED = "BotRequestCancelled";
    private static final int MAX_STARTUP_RETRIES = 4;
    private static final long CANCELLED_TOKEN_RETENTION_MILLIS = 60_000L;

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

    // Bot loops share a bounded worker pool. Each handle serializes its startup,
    // game loop and final cleanup even when the periodic task migrates between workers.
    private final Map<UUID, BotLoopScheduler.LoopHandle> botLoopHandles = new ConcurrentHashMap<>();

    // A cancellation can overtake a queued create/retry task, so remember it briefly.
    private final Map<String, Long> cancelledRequestTokens = new ConcurrentHashMap<>();

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
            gg.mineral.bot.base.client.instance.BungeeGuardForwarding forwarding,
            int gameLoopWorkers,
            boolean timingDiagnosticsEnabled,
            boolean velocityInputRecoveryEnabled
    ) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.guideEnabled = guideEnabled;
        this.forwarding = forwarding;
        this.timingDiagnosticsEnabled = timingDiagnosticsEnabled;
        this.velocityInputRecoveryEnabled = velocityInputRecoveryEnabled;
        int startupWorkers = Math.max(2, Math.min(4, gameLoopWorkers));
        this.loopScheduler = new BotLoopScheduler(gameLoopWorkers, startupWorkers);

        logger.info(
                "VelocityBotManager initialized (guide-enabled={}, connection-mode=direct-backend-bungeeguard, game-loop-workers={}, "
                        + "startup-workers={}, timing-diagnostics={}, velocity-input-recovery-enabled={})",
                guideEnabled,
                gameLoopWorkers,
                startupWorkers,
                timingDiagnosticsEnabled,
                velocityInputRecoveryEnabled
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
        if (!diagnostics.sendControl(out.toByteArray())) {
            sendPluginMessageToPlayerServer(diagnostics.getPlayerUuid(), out.toByteArray());
        }
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
        if (!diagnostics.sendControl(out.toByteArray())) {
            sendPluginMessageToPlayerServer(diagnostics.getPlayerUuid(), out.toByteArray());
        }
    }

    private void scheduleBotRecreate(
            UUID playerUUID,
            DirectTarget target,
            String kitType,
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int nextRetryCount,
            long delayMillis
    ) {
        if (shuttingDown.get()) {
            return;
        }
        server.getScheduler().buildTask(plugin, () -> {
            if (!shuttingDown.get() && !isRequestCancelled(requestToken)) {
                createAndConnectBot(playerUUID, target, kitType, difficulty, requestToken, latencyMillis,
                        nextRetryCount);
            }
        }).delay(delayMillis, TimeUnit.MILLISECONDS).schedule();
    }

    private long retryDelayMillis(int retryCount, boolean frequentConnectionKick) {
        if (frequentConnectionKick) {
            // Initial connections remain fully parallel. Only throttled retries are
            // spread far enough apart to avoid a reconnect storm from the same host.
            return Math.min(4_000L, 1_050L + retryCount * 650L)
                    + ThreadLocalRandom.current().nextLong(150L, 750L);
        }
        long exponentialDelay = Math.min(2_400L, 300L << Math.min(retryCount, 3));
        return exponentialDelay + ThreadLocalRandom.current().nextLong(100L, 350L);
    }

    private boolean isRequestCancelled(String requestToken) {
        if (requestToken == null) {
            return false;
        }

        Long expiresAt = cancelledRequestTokens.get(requestToken);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt <= System.currentTimeMillis()) {
            cancelledRequestTokens.remove(requestToken, expiresAt);
            return false;
        }
        return true;
    }

    private void purgeExpiredCancelledTokens() {
        long now = System.currentTimeMillis();
        cancelledRequestTokens.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private boolean runStartupWatchdog(UUID botUuid) {
        BotSessionDiagnostics diagnostics = getDiagnostics(botUuid);
        if (diagnostics == null) {
            return false;
        }

        long now = System.currentTimeMillis();

        if (diagnostics.shouldWarnStartup(now)) {
            diagnostics.markStartupWarningLogged();
            if (logger.isDebugEnabled()) {
                logger.debug("Bot startup missing packets. {}", diagnostics.startupSummary());
            }
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

    private void runTargetWatchdog(UUID botUuid, ClientInstance bot, int advancedTicks) {
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

        diagnostics.noteTargetMissingTicks(advancedTicks);

        if (diagnostics.shouldLogTargetLoss()) {
            diagnostics.markTargetLossLogged();
            logger.warn("Bot {} lost target {}", botUuid, expectedTargetUuid);
            if (logger.isDebugEnabled()) {
                logger.debug("Bot target diagnostics. {}", diagnostics.targetSummary(false));
            }
        }

        long now = System.currentTimeMillis();
        if (diagnostics.shouldRequestRepair(now)) {
            diagnostics.markRepairRequested(now);
            logger.debug("Requesting bot match entity repair: bot={}, target={}", botUuid, expectedTargetUuid);
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
            logger.debug("Refreshed friendly UUID aliases for bot {}: {}", ourBotUuid, resolvedFriendlies);
        }
    }

    private UUID findCandidateBotUuid(UUID playerUUID, String kitType, BotDifficulty difficulty, String requestToken) {
        UUID legacyCandidate = null;
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

            if ((requestToken == null || requestToken.isEmpty()) && candidateKit != null
                    && candidateKit.equalsIgnoreCase(kitType)
                    && candidateDifficulty == difficulty) {
                legacyCandidate = candidateUuid;
            }
        }
        return legacyCandidate;
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
                || text.contains("\u7a0d\u540e\u518d\u8bd5")
                || text.toLowerCase(Locale.ROOT).contains("connection throttled")
                || text.toLowerCase(Locale.ROOT).contains("logging in too fast")
                || text.toLowerCase(Locale.ROOT).contains("too many connections"));
    }

    private void cleanupBot(UUID ourBotUUID, UUID serverBotUUID, boolean shutdownInstance) {
        cleanupBot(ourBotUUID, serverBotUUID, shutdownInstance, false, () -> { });
    }

    private void cleanupBot(
            UUID ourBotUUID,
            UUID serverBotUUID,
            boolean shutdownInstance,
            boolean interruptLoop
    ) {
        cleanupBot(ourBotUUID, serverBotUUID, shutdownInstance, interruptLoop, () -> { });
    }

    private void cleanupBot(
            UUID ourBotUUID,
            UUID serverBotUUID,
            boolean shutdownInstance,
            boolean interruptLoop,
            Runnable afterCleanup
    ) {
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

        String username = findUsername(ourBotUUID);
        if (username != null) {
            botsByUsername.remove(username);
        }

        BotLoopScheduler.LoopHandle loopHandle = botLoopHandles.remove(ourBotUUID);
        Runnable finalizer = () -> {
            if (shutdownInstance && bot != null) {
                shutdownBotInstance(ourBotUUID, bot);
            }
            try {
                afterCleanup.run();
            } catch (Exception completionFailure) {
                logger.error("Bot {} cleanup completion failed", ourBotUUID, completionFailure);
            }
        };
        if (loopHandle != null) {
            loopScheduler.stop(loopHandle, interruptLoop, finalizer);
        } else {
            finalizer.run();
        }
    }

    private void shutdownBotInstance(UUID botUuid, ClientInstance bot) {
        try {
            File runDir = bot.mcDataDir;
            bot.setBackendControlListener(null);
            bot.setPacketDiagnosticsListener(null);
            bot.setTimingDiagnosticsListener(null);
            closeBotNetwork(bot, "MineralBot cleanup");
            bot.shutdown();

            if (runDir != null && runDir.exists()) {
                try {
                    org.apache.commons.io.FileUtils.deleteDirectory(runDir);
                    logger.debug("Deleted bot run directory: {}", runDir.getAbsolutePath());
                } catch (Exception e) {
                    logger.warn("Failed to delete bot run directory: {}", runDir.getAbsolutePath(), e);
                }
            }
        } catch (Exception e) {
            logger.error("Error shutting down bot {}", botUuid, e);
        }
    }

    private boolean handleDisconnectedBot(
            UUID ourBotUUID,
            ClientInstance bot,
            UUID playerUUID,
            DirectTarget target,
            String kitType,
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int retryCount,
            String botUsername
    ) {
        String disconnectText = forwarding.redact(getDisconnectText(bot));
        if (disconnectText == null) {
            return false;
        }

        BotSessionDiagnostics diagnostics = getDiagnostics(ourBotUUID);
        if (diagnostics != null) {
            diagnostics.noteDisconnect(disconnectText);
        }
        boolean duelStarted = diagnostics != null && diagnostics.isDuelStarted();
        boolean requestAccepted = diagnostics != null && diagnostics.isRequestAccepted();

        logger.warn("Bot {} disconnected at {}: {}", botUsername, bot.getDirectConnectionStage(), disconnectText);
        boolean frequentKick = isFrequentConnectionKick(disconnectText);
        boolean timeoutLike = isTimeoutLike(disconnectText);

        cleanupBot(ourBotUUID, null, true);

        if (!duelStarted && !requestAccepted && retryCount < MAX_STARTUP_RETRIES
                && !isRequestCancelled(requestToken) && !isPermanentLoginFailure(disconnectText)) {
            logger.warn("Bot {} disconnected before backend acceptance, recreating (retry #{}, reason={})",
                    botUsername, retryCount + 1, disconnectText);
            scheduleBotRecreate(playerUUID, target, kitType, difficulty, requestToken, latencyMillis,
                    retryCount + 1, retryDelayMillis(retryCount, frequentKick));
        } else if (diagnostics != null) {
            String reason = frequentKick ? "frequent-connection-kick" : timeoutLike ? "read-timeout" : "bot-disconnected";
            if (duelStarted || requestAccepted) {
                logger.debug("Bot {} disconnected after backend acceptance; backend will handle replacement/cleanup. reason={}",
                        botUsername, reason);
            }
            if (!requestAccepted && !isRequestCancelled(requestToken)) {
                notifyBotDuelFailed(diagnostics, reason, disconnectText);
            }
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

        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof com.velocitypowered.api.proxy.ServerConnection source)) {
            return;
        }
        handleBackendMessage(event.getData(), source.getServer(), true);
    }

    private void handleBackendMessage(byte[] payload,
            com.velocitypowered.api.proxy.server.RegisteredServer source, boolean allowCreate) {
        if (shuttingDown.get()) return;
        ByteArrayDataInput in = ByteStreams.newDataInput(payload);
        String subChannel;
        try {
            subChannel = in.readUTF();
            // logger.info("Plugin Message received on channel: {} with subchannel: {}",
            // identifier.getId(), subChannel);
        } catch (Exception e) {
            logger.warn("Invalid MineralBot control message");
            return;
        }

        switch (subChannel) {
            case SUB_CHANNEL_BOT_DUEL:
                if (allowCreate) handleBotDuelRequest(in, source);
                break;
            case SUB_CHANNEL_BOT_DUEL_STARTED:
                handleBotDuelStarted(in);
                break;
            case SUB_CHANNEL_BOT_REQUEST_ACCEPTED:
                handleBotRequestAccepted(in);
                break;
            case SUB_CHANNEL_BOT_REQUEST_CANCELLED:
                handleBotRequestCancelled(in);
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
                logger.debug("Ignored subchannel: {}", subChannel);
        }
    }


    private void handleBotDuelRequest(ByteArrayDataInput in, com.velocitypowered.api.proxy.server.RegisteredServer source) {
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
            var registered = server.getServer(serverName).orElse(null);
            BotSessionDiagnostics rejected = new BotSessionDiagnostics(
                    playerUUID, UUID.randomUUID(), "uncreated", requestToken, 0);
            if (registered == null || !source.getServerInfo().equals(registered.getServerInfo())) {
                notifyBotDuelFailed(rejected, "TARGET_NOT_FOUND", "Request target must match its registered source server.");
                return;
            }
            rejected.setControlSender(payload -> source.sendPluginMessage(MineralBotVelocity.MINERAL_BOT_CHANNEL, payload));
            var address = registered.getServerInfo().getAddress();
            if (address.getPort() < 1 || address.getHostString().isBlank()
                    || address.getHostString().indexOf('\0') >= 0) {
                notifyBotDuelFailed(rejected, "TARGET_ADDRESS_INVALID", "Invalid registered backend address.");
                return;
            }
            if (forwarding == null) {
                notifyBotDuelFailed(rejected, "SECRET_UNAVAILABLE", "Direct backend credentials are not configured.");
                return;
            }
            DirectTarget target = new DirectTarget(serverName, address, registered);

            if (isRequestCancelled(requestToken)) {
                logger.debug("Ignoring cancelled BotDuel request. Player={}, Token={}", playerUUID, requestToken);
                return;
            }

            logger.debug("Received BotDuel request: Player={}, Server={}, Kit={}, Difficulty={}, Latency={}ms, Token={}",
                    playerUUID, target, kitType, difficulty.getId(), latencyMillis, requestToken);

            createAndConnectBot(playerUUID, target, kitType, difficulty, requestToken, latencyMillis, 0);
        } catch (Exception e) {
            logger.error("Failed to parse BotDuel message", e);
        }
    }

    private void handleBotRequestAccepted(ByteArrayDataInput in) {
        try {
            UUID serverBotUuid = UUID.fromString(in.readUTF());
            String requestToken = in.readUTF();

            UUID ourBotUuid = requestToken == null || requestToken.isEmpty()
                    ? resolveToOurUuid(serverBotUuid)
                    : findBotByRequestToken(requestToken);
            if (ourBotUuid != null && !activeBots.containsKey(ourBotUuid)) {
                ourBotUuid = null;
            }
            if (ourBotUuid == null) {
                logger.warn("Could not acknowledge bot request. Server UUID={}, Token={}", serverBotUuid, requestToken);
                return;
            }

            serverUuidToOurUuid.put(serverBotUuid, ourBotUuid);
            BotSessionDiagnostics diagnostics = getDiagnostics(ourBotUuid);
            if (diagnostics != null) {
                diagnostics.markRequestAccepted();
            }
            refreshAllFriendlyUuidMappings();
            logger.debug("Backend accepted bot request. ourUUID={}, serverUUID={}, token={}",
                    ourBotUuid, serverBotUuid, requestToken);
        } catch (Exception e) {
            logger.error("Failed to parse BotRequestAccepted message", e);
        }
    }

    private void handleBotRequestCancelled(ByteArrayDataInput in) {
        try {
            String requestToken = in.readUTF();
            purgeExpiredCancelledTokens();
            cancelledRequestTokens.put(requestToken,
                    System.currentTimeMillis() + CANCELLED_TOKEN_RETENTION_MILLIS);

            Set<UUID> matchingBots = new HashSet<>();
            for (Map.Entry<UUID, String> entry : botRequestTokens.entrySet()) {
                if (requestToken.equalsIgnoreCase(entry.getValue())) {
                    matchingBots.add(entry.getKey());
                }
            }
            for (UUID botUuid : matchingBots) {
                cleanupBot(botUuid, null, true);
            }
            logger.debug("Cancelled bot request token {} (active bots stopped={})", requestToken, matchingBots.size());
        } catch (Exception e) {
            logger.error("Failed to parse BotRequestCancelled message", e);
        }
    }

    private UUID findBotByRequestToken(String requestToken) {
        if (requestToken == null) {
            return null;
        }
        for (Map.Entry<UUID, String> entry : botRequestTokens.entrySet()) {
            if (requestToken.equalsIgnoreCase(entry.getValue()) && activeBots.containsKey(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
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

            logger.debug("BotDuel started: Owner={}, Target={}, Bot(server)={}, Kit={}, Difficulty={}, Token={}, Friendlies={}",
                    playerUUID, targetUUID, serverBotUUID, kitType, difficulty.getId(), requestToken, friendlyUUIDs);

            boolean hasRequestToken = requestToken != null && !requestToken.isEmpty();
            UUID ourBotUUID = hasRequestToken
                    ? findBotByRequestToken(requestToken)
                    : resolveToOurUuid(serverBotUUID);
            ClientInstance bot = ourBotUUID == null ? null : activeBots.get(ourBotUUID);

            if (!hasRequestToken && !isBotUsable(bot)) {
                logger.debug("Bot not found by server UUID, searching by player UUID, difficulty, and request token...");
                UUID candidateUuid = findCandidateBotUuid(playerUUID, kitType, difficulty, requestToken);
                if (candidateUuid == null) {
                    logger.debug("No exact player/token match found, falling back to kit and difficulty...");
                    candidateUuid = findUsableBotByKit(kitType, difficulty);
                }

                if (candidateUuid != null) {
                    ourBotUUID = candidateUuid;
                    bot = activeBots.get(ourBotUUID);
                    serverUuidToOurUuid.put(serverBotUUID, ourBotUUID);
                    logger.debug("Matched bot: ourUUID={}, serverUUID={}", ourBotUUID, serverBotUUID);
                }
            }

            if (!isBotUsable(bot)) {
                logger.warn("Could not find active bot for BotDuelStarted. Server UUID: {}", serverBotUUID);
                logger.debug("Active bots: {}", activeBots.keySet());
                logger.debug("Kit types: {}", kitTypes);
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

            logger.debug("Configuring combat AI for bot {} with kit type {} at difficulty {}", ourBotUUID, kitType,
                    difficulty.getId());
            PracticeAI.INSTANCE.configureBotForKit(bot, kitType, difficulty);
            logger.info("Bot duel started: bot={}, kit={}, difficulty={}", ourBotUUID, kitType, difficulty.getId());
        } catch (Exception e) {
            logger.error("Failed to parse BotDuelStarted message", e);
        }
    }
    private void handleBotDisconnect(ByteArrayDataInput in) {
        try {
            String botUUIDStr = in.readUTF();
            UUID serverBotUUID = UUID.fromString(botUUIDStr);

            logger.debug("Received BotDisconnect request for: {}", serverBotUUID);

            UUID ourBotUUID = resolveToOurUuid(serverBotUUID);
            logger.debug("Resolved to our UUID: {}", ourBotUUID);

            cleanupBot(ourBotUUID, serverBotUUID, true);
        } catch (Exception e) {
            logger.error("Failed to parse BotDisconnect message", e);
        }
    }
    private void createAndConnectBot(
            UUID playerUUID,
            DirectTarget target,
            String kitType,
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int retryCount
    ) {
        if (shuttingDown.get() || isRequestCancelled(requestToken)) {
            return;
        }

        UUID botUUID = UUID.randomUUID();
        String botUsername = generateUniqueBotUsername(requestToken);
        BotSessionDiagnostics diagnostics =
                new BotSessionDiagnostics(playerUUID, botUUID, botUsername, requestToken, retryCount);
        diagnostics.setControlSender(payload -> target.server().sendPluginMessage(
                MineralBotVelocity.MINERAL_BOT_CHANNEL, payload));
        BotLoopScheduler.LoopHandle loopHandle;
        try {
            loopHandle = loopScheduler.createHandle(
                    botUUID,
                    (stalledBotUuid, blockedNanos) -> handleBotLoopStall(
                            stalledBotUuid, blockedNanos, playerUUID, target, kitType, difficulty,
                            requestToken, latencyMillis, retryCount, botUsername, diagnostics),
                    (failedBotUuid, failure) -> handleBotLoopFailure(
                            failedBotUuid, failure, playerUUID, target, kitType, difficulty,
                            requestToken, latencyMillis, retryCount, botUsername, diagnostics, false));
            botLoopHandles.put(botUUID, loopHandle);
        } catch (RejectedExecutionException rejected) {
            if (!shuttingDown.get() && !isRequestCancelled(requestToken)) {
                notifyBotDuelFailed(diagnostics, "scheduler-closed", rejected.toString());
            }
            return;
        }

        try {
            loopScheduler.submitStartup(loopHandle, () -> {
                try {
                    if (shuttingDown.get() || isRequestCancelled(requestToken)) {
                        cleanupBot(botUUID, null, false);
                        return;
                    }

                BotConfiguration config = new BotConfiguration();
                if (retryCount > 0) {
                    logger.info("Retrying bot {} (retry #{})", botUsername, retryCount);
                }
                config.setUuid(botUUID);
                config.setUsername(botUsername);
                config.setDebug(false);
                difficulty.applyTo(config);
                config.setLatency(latencyMillis);
                config.setVelocityInputRecoveryEnabled(velocityInputRecoveryEnabled);

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
                if (timingDiagnosticsEnabled) {
                    bot.setTimingDiagnosticsListener((event, queuedNanos, velocityX, velocityY, velocityZ) ->
                            diagnostics.recordTimingEvent(event, queuedNanos, velocityX, velocityY, velocityZ));
                }
                bot.setBungeeGuardForwarding(forwarding);
                bot.setBackendControlListener(payload -> {
                    if (activeBots.get(botUUID) == bot) handleBackendMessage(payload, target.server(), false);
                });
                bot.setServer(target.address().getHostString(), target.address().getPort());
                diagnostics.noteConnectionTarget(target.name(), target.address().getHostString(),
                        target.address().getPort(), forwarding.getForwardedIp());
                logger.info("Direct bot connection: server={}, host={}, port={}, uuid={}, forwardedIp={}",
                        target.name(), target.address().getHostString(), target.address().getPort(),
                        botUUID, forwarding.getForwardedIp());

                activeBots.put(botUUID, bot);
                botTargets.put(botUUID, playerUUID);
                kitTypes.put(botUUID, kitType);
                botDifficulties.put(botUUID, difficulty);
                botRequestTokens.put(botUUID, requestToken);
                botDiagnostics.put(botUUID, diagnostics);
                botsByUsername.put(config.getUsername(), botUUID);

                if (shuttingDown.get() || isRequestCancelled(requestToken)) {
                    cleanupBot(botUUID, null, true);
                    return;
                }

                logger.debug("Starting bot instance for {} (UUID: {}, Difficulty: {}, Latency: {}ms, Token: {})",
                        config.getUsername(), botUUID, difficulty.getId(), latencyMillis, requestToken);
                bot.run();

                loopScheduler.schedule(
                        loopHandle,
                        () -> runBotGameLoop(botUUID, bot, playerUUID, target, kitType, difficulty,
                                requestToken, latencyMillis, retryCount, botUsername));
                } catch (Exception e) {
                    logger.error("Failed to start bot {}", botUsername, e);
                    cleanupBot(botUUID, null, true);
                    if (shuttingDown.get() || isRequestCancelled(requestToken)) {
                        return;
                    }
                    if (retryCount < MAX_STARTUP_RETRIES && !(e instanceof IllegalArgumentException)) {
                        scheduleBotRecreate(playerUUID, target, kitType, difficulty, requestToken, latencyMillis,
                                retryCount + 1, retryDelayMillis(retryCount, false));
                    } else {
                        notifyBotDuelFailed(diagnostics, "create-failed", e.toString());
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            botLoopHandles.remove(botUUID, loopHandle);
            loopScheduler.stop(loopHandle, false, () -> { });
            if (!shuttingDown.get() && !isRequestCancelled(requestToken)) {
                logger.error("Bot startup queue is full for {}", botUsername, rejected);
                notifyBotDuelFailed(diagnostics, "startup-queue-full", rejected.toString());
            }
        }
    }

    private void runBotGameLoop(
            UUID botUUID,
            ClientInstance bot,
            UUID playerUUID,
            DirectTarget target,
            String kitType,
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int retryCount,
            String botUsername
    ) {
        if (shuttingDown.get() || isRequestCancelled(requestToken)) {
            cleanupBot(botUUID, null, true);
            return;
        }

        if (!bot.isRunning()) {
            BotSessionDiagnostics diagnostics = getDiagnostics(botUUID);
            boolean accepted = diagnostics != null && diagnostics.isRequestAccepted();
            cleanupBot(botUUID, null, false);
            if (!shuttingDown.get() && !accepted && retryCount < MAX_STARTUP_RETRIES) {
                scheduleBotRecreate(playerUUID, target, kitType, difficulty, requestToken, latencyMillis,
                        retryCount + 1, retryDelayMillis(retryCount, false));
            } else if (!accepted && diagnostics != null && !isRequestCancelled(requestToken)) {
                notifyBotDuelFailed(diagnostics, "bot-stopped", "Bot instance stopped before backend acceptance.");
            }
            return;
        }

        BotSessionDiagnostics connectionDiagnostics = getDiagnostics(botUUID);
        if (connectionDiagnostics != null) {
            if (connectionDiagnostics.noteConnectionStage(bot.getDirectConnectionStage())) {
                logger.info("Bot {} connection stage: {}", botUsername, bot.getDirectConnectionStage());
            }
        }
        int tickBefore = bot.getCurrentTick();
        long loopStartedAt = timingDiagnosticsEnabled ? System.nanoTime() : 0L;
        bot.runGameLoop();
        int advancedTicks = Math.max(0, bot.getCurrentTick() - tickBefore);

        if (timingDiagnosticsEnabled) {
            recordLoopTiming(botUUID, loopStartedAt, advancedTicks);
        }

        // The outer task is only a high-frequency pump. Maintenance that used to
        // run once per 50 ms must remain tied to actual Minecraft logical ticks.
        if (advancedTicks == 0) {
            return;
        }

        if (handleDisconnectedBot(botUUID, bot, playerUUID, target, kitType, difficulty,
                requestToken, latencyMillis, retryCount, botUsername)) {
            return;
        }
        if (runStartupWatchdog(botUUID)) {
            return;
        }
        runTargetWatchdog(botUUID, bot, advancedTicks);
    }

    private void recordLoopTiming(UUID botUuid, long loopStartedAtNanos, int advancedTicks) {
        BotSessionDiagnostics diagnostics = getDiagnostics(botUuid);
        if (diagnostics == null) {
            return;
        }

        BotSessionDiagnostics.TimingLog timingLog = diagnostics.recordGameLoop(
                loopStartedAtNanos,
                System.nanoTime(),
                advancedTicks);
        if (timingLog.anomaly() != null) {
            logger.warn("Bot timing anomaly. {}", timingLog.anomaly());
        }
        if (timingLog.summary() != null) {
            logger.debug("Bot timing summary. {}", timingLog.summary());
        }
    }

    private void handleBotLoopStall(
            UUID botUuid,
            long blockedNanos,
            UUID playerUUID,
            DirectTarget target,
            String kitType,
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int retryCount,
            String botUsername,
            BotSessionDiagnostics initialDiagnostics
    ) {
        handleBotLoopFailure(
                botUuid,
                new IllegalStateException("runGameLoop blocked for "
                        + TimeUnit.NANOSECONDS.toMillis(blockedNanos) + "ms"),
                playerUUID,
                target,
                kitType,
                difficulty,
                requestToken,
                latencyMillis,
                retryCount,
                botUsername,
                initialDiagnostics,
                true);
    }

    private void handleBotLoopFailure(
            UUID botUuid,
            Throwable failure,
            UUID playerUUID,
            DirectTarget target,
            String kitType,
            BotDifficulty difficulty,
            String requestToken,
            int latencyMillis,
            int retryCount,
            String botUsername,
            BotSessionDiagnostics initialDiagnostics,
            boolean interruptLoop
    ) {
        if (!botLoopHandles.containsKey(botUuid)) {
            return;
        }

        BotSessionDiagnostics diagnostics = getDiagnostics(botUuid);
        if (diagnostics == null) {
            diagnostics = initialDiagnostics;
        }
        if (diagnostics != null) {
            diagnostics.noteDisconnect(forwarding.redact(failure.toString()));
        }
        boolean accepted = diagnostics != null && diagnostics.isRequestAccepted();
        String reason = interruptLoop ? "game-loop-stalled" : "game-loop-failed";
        logger.error("Bot {} {} (accepted={}): {}", botUsername, reason, accepted, forwarding.redact(failure.toString()));
        boolean retryAfterCleanup = !accepted && retryCount < MAX_STARTUP_RETRIES
                && !isPermanentLoginFailure(failure.toString());
        if (!retryAfterCleanup && diagnostics != null
                && !shuttingDown.get() && !isRequestCancelled(requestToken)) {
            notifyBotDuelFailed(diagnostics, reason, forwarding.redact(failure.toString()));
        }
        cleanupBot(botUuid, null, true, interruptLoop, () -> {
            if (retryAfterCleanup && !shuttingDown.get() && !isRequestCancelled(requestToken)) {
                scheduleBotRecreate(playerUUID, target, kitType, difficulty, requestToken, latencyMillis,
                        retryCount + 1, retryDelayMillis(retryCount, false));
            }
        });
    }

    private String generateUniqueBotUsername(String requestToken) {
        String token = requestToken == null ? "BOT0" : requestToken.toUpperCase(Locale.ROOT);
        return "_" + token;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        Set<UUID> botUuids = new HashSet<>(botLoopHandles.keySet());
        botUuids.addAll(activeBots.keySet());
        logger.info("Stopping {} bot sessions before proxy shutdown", botUuids.size());
        for (UUID botUuid : botUuids) {
            cleanupBot(botUuid, null, true, true);
        }
        loopScheduler.closeGracefully(Duration.ofSeconds(5L));
    }

}
