package com.mineralstudios.bot.velocity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class BotSessionDiagnostics {

    static final String PLAYER_POS_LOOK = "PLAYER_POS_LOOK";
    static final String SPAWN_PLAYER = "SPAWN_PLAYER";
    static final String ENTITY_MOVEMENT = "ENTITY_MOVEMENT";
    static final String ENTITY_METADATA = "ENTITY_METADATA";
    static final String ENTITY_VELOCITY = "ENTITY_VELOCITY";
    static final String DESTROY_ENTITIES = "DESTROY_ENTITIES";
    static final String SET_SLOT = "SET_SLOT";
    static final String WINDOW_ITEMS = "WINDOW_ITEMS";
    static final String UPDATE_HEALTH = "UPDATE_HEALTH";

    static final long STARTUP_WARNING_AFTER_MILLIS = 3_000L;
    static final long STARTUP_FAILURE_AFTER_MILLIS = 12_000L;
    static final int TARGET_MISSING_TICK_THRESHOLD = 40;
    static final long TARGET_REPAIR_COOLDOWN_MILLIS = 2_000L;

    private static final List<String> PACKET_KEYS = Arrays.asList(
            PLAYER_POS_LOOK,
            SPAWN_PLAYER,
            ENTITY_MOVEMENT,
            ENTITY_METADATA,
            ENTITY_VELOCITY,
            DESTROY_ENTITIES,
            SET_SLOT,
            WINDOW_ITEMS,
            UPDATE_HEALTH
    );

    private final UUID playerUuid;
    private final UUID ourBotUuid;
    private final String botUsername;
    private final String requestToken;
    private final int retryCount;
    private final long createdAtMillis = System.currentTimeMillis();
    private final Map<String, Integer> packetCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPacketSeenAtMillis = new ConcurrentHashMap<>();

    private volatile boolean duelStarted;
    private volatile long duelStartedAtMillis = -1L;
    private volatile String lastDisconnectReason = "";
    private volatile UUID expectedTargetUuid;
    private volatile Integer expectedTargetEntityId;
    private volatile long lastTargetSeenAtMillis = -1L;
    private volatile long lastTargetRelevantPacketAtMillis = -1L;
    private volatile long lastTargetMovementPacketAtMillis = -1L;
    private volatile long lastTargetDestroyPacketAtMillis = -1L;
    private volatile long lastRepairRequestAtMillis = -1L;
    private volatile int consecutiveMissingTargetTicks;
    private volatile boolean startupWarningLogged;
    private volatile boolean failureReported;
    private volatile boolean targetWarningLoggedForCurrentMissingEpisode;
    private volatile boolean repairRequestedForCurrentMissingEpisode;

    BotSessionDiagnostics(UUID playerUuid, UUID ourBotUuid, String botUsername, String requestToken, int retryCount) {
        this.playerUuid = playerUuid;
        this.ourBotUuid = ourBotUuid;
        this.botUsername = botUsername;
        this.requestToken = requestToken;
        this.retryCount = retryCount;
    }

    UUID getPlayerUuid() {
        return playerUuid;
    }

    UUID getOurBotUuid() {
        return ourBotUuid;
    }

    String getBotUsername() {
        return botUsername;
    }

    String getRequestToken() {
        return requestToken;
    }

    int getRetryCount() {
        return retryCount;
    }

    boolean isDuelStarted() {
        return duelStarted;
    }

    synchronized void markPacket(String packetKey) {
        markPacket(packetKey, Integer.MIN_VALUE, null);
    }

    synchronized void markPacket(String packetKey, int entityId, UUID entityUuid) {
        long now = System.currentTimeMillis();
        packetCounts.merge(packetKey, 1, Integer::sum);
        lastPacketSeenAtMillis.put(packetKey, now);
        trackTargetPacket(packetKey, entityId, entityUuid, now);
    }

    synchronized void setExpectedTargetUuid(UUID targetUuid) {
        if (targetUuid != null) {
            this.expectedTargetUuid = targetUuid;
        }
    }

    synchronized void markDuelStarted(UUID targetUuid) {
        this.duelStarted = true;
        this.duelStartedAtMillis = System.currentTimeMillis();
        this.expectedTargetUuid = targetUuid;
    }

    void noteDisconnect(String disconnectReason) {
        this.lastDisconnectReason = disconnectReason == null ? "" : disconnectReason;
    }

    synchronized void noteTargetSeen(int entityId) {
        long now = System.currentTimeMillis();
        this.expectedTargetEntityId = entityId;
        this.lastTargetSeenAtMillis = now;
        this.consecutiveMissingTargetTicks = 0;
        this.targetWarningLoggedForCurrentMissingEpisode = false;
        this.repairRequestedForCurrentMissingEpisode = false;
    }

    synchronized int noteTargetMissingTick() {
        return ++consecutiveMissingTargetTicks;
    }

    synchronized boolean shouldLogTargetLoss() {
        return consecutiveMissingTargetTicks >= TARGET_MISSING_TICK_THRESHOLD
                && !targetWarningLoggedForCurrentMissingEpisode;
    }

    synchronized void markTargetLossLogged() {
        this.targetWarningLoggedForCurrentMissingEpisode = true;
    }

    synchronized boolean shouldRequestRepair(long now) {
        return consecutiveMissingTargetTicks >= TARGET_MISSING_TICK_THRESHOLD
                && !repairRequestedForCurrentMissingEpisode
                && (lastRepairRequestAtMillis <= 0
                        || now - lastRepairRequestAtMillis >= TARGET_REPAIR_COOLDOWN_MILLIS);
    }

    synchronized void markRepairRequested(long now) {
        this.lastRepairRequestAtMillis = now;
        this.repairRequestedForCurrentMissingEpisode = true;
    }

    synchronized boolean shouldWarnStartup(long now) {
        return !startupWarningLogged
                && now - createdAtMillis >= STARTUP_WARNING_AFTER_MILLIS
                && !getMissingStartupPacketsInternal().isEmpty();
    }

    synchronized void markStartupWarningLogged() {
        this.startupWarningLogged = true;
    }

    synchronized boolean shouldFailStartup(long now) {
        return !duelStarted
                && !failureReported
                && now - createdAtMillis >= STARTUP_FAILURE_AFTER_MILLIS;
    }

    synchronized boolean markFailureReported() {
        if (failureReported) {
            return false;
        }
        this.failureReported = true;
        return true;
    }

    synchronized List<String> getMissingStartupPackets() {
        return new ArrayList<>(getMissingStartupPacketsInternal());
    }

    synchronized String startupSummary() {
        return baseFields()
                + ", missingStartup=" + getMissingStartupPacketsInternal()
                + ", packets={" + packetSummary() + "}"
                + ", lastDisconnectReason=\"" + sanitize(lastDisconnectReason) + "\"";
    }

    synchronized String targetSummary(boolean targetPresentInWorld) {
        return baseFields()
                + ", targetPresentInWorld=" + targetPresentInWorld
                + ", expectedTargetUuid=" + expectedTargetUuid
                + ", expectedTargetEntityId=" + expectedTargetEntityId
                + ", consecutiveMissingTargetTicks=" + consecutiveMissingTargetTicks
                + ", lastTargetSeenAtMillis=" + lastTargetSeenAtMillis
                + ", lastTargetRelevantPacketAtMillis=" + lastTargetRelevantPacketAtMillis
                + ", lastTargetMovementPacketAtMillis=" + lastTargetMovementPacketAtMillis
                + ", lastTargetDestroyPacketAtMillis=" + lastTargetDestroyPacketAtMillis
                + ", packets={" + packetSummary() + "}"
                + ", lastDisconnectReason=\"" + sanitize(lastDisconnectReason) + "\"";
    }

    private void trackTargetPacket(String packetKey, int entityId, UUID entityUuid, long now) {
        if (expectedTargetUuid == null) {
            return;
        }

        boolean uuidMatch = entityUuid != null && expectedTargetUuid.equals(entityUuid);
        boolean idMatch = expectedTargetEntityId != null && entityId != Integer.MIN_VALUE
                && expectedTargetEntityId.intValue() == entityId;

        if (uuidMatch && entityId != Integer.MIN_VALUE) {
            expectedTargetEntityId = entityId;
        }

        if (!uuidMatch && !idMatch) {
            return;
        }

        lastTargetRelevantPacketAtMillis = now;

        if (SPAWN_PLAYER.equals(packetKey)
                || ENTITY_MOVEMENT.equals(packetKey)
                || ENTITY_METADATA.equals(packetKey)
                || ENTITY_VELOCITY.equals(packetKey)) {
            lastTargetMovementPacketAtMillis = now;
        }

        if (DESTROY_ENTITIES.equals(packetKey)) {
            lastTargetDestroyPacketAtMillis = now;
            if (idMatch) {
                expectedTargetEntityId = null;
            }
        }
    }

    private List<String> getMissingStartupPacketsInternal() {
        List<String> missing = new ArrayList<>();
        if (!hasSeen(PLAYER_POS_LOOK)) {
            missing.add(PLAYER_POS_LOOK);
        }
        if (!hasSeen(SPAWN_PLAYER)) {
            missing.add(SPAWN_PLAYER);
        }
        if (!hasSeen(ENTITY_MOVEMENT)) {
            missing.add(ENTITY_MOVEMENT);
        }
        if (!hasSeen(SET_SLOT) && !hasSeen(WINDOW_ITEMS)) {
            missing.add("INVENTORY_SYNC");
        }
        return missing;
    }

    private boolean hasSeen(String packetKey) {
        return packetCounts.getOrDefault(packetKey, 0) > 0;
    }

    private String packetSummary() {
        List<String> parts = new ArrayList<>(PACKET_KEYS.size());
        for (String packetKey : PACKET_KEYS) {
            parts.add(packetKey + "="
                    + packetCounts.getOrDefault(packetKey, 0)
                    + "@"
                    + lastPacketSeenAtMillis.getOrDefault(packetKey, -1L));
        }
        return String.join(", ", parts);
    }

    private String baseFields() {
        return "playerUuid=" + playerUuid
                + ", ourBotUuid=" + ourBotUuid
                + ", botUsername=" + botUsername
                + ", requestToken=" + requestToken
                + ", retryCount=" + retryCount
                + ", duelStarted=" + duelStarted
                + ", createdAtMillis=" + createdAtMillis
                + ", duelStartedAtMillis=" + duelStartedAtMillis;
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
