package com.mineralstudios.bot.velocity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
    static final long STARTUP_FAILURE_AFTER_MILLIS = 30_000L;
    static final int TARGET_MISSING_TICK_THRESHOLD = 40;
    static final long TARGET_REPAIR_COOLDOWN_MILLIS = 2_000L;
    private static final long TIMING_SUMMARY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long TIMING_ANOMALY_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long SLOW_LOOP_NANOS = TimeUnit.MILLISECONDS.toNanos(20L);
    private static final long SLOW_S12_QUEUE_NANOS = TimeUnit.MILLISECONDS.toNanos(75L);

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
    private volatile boolean requestAccepted;
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

    private long timingWindowStartedAtNanos;
    private long previousPumpStartedAtNanos;
    private long lastTimingAnomalyAtNanos;
    private long pumpCount;
    private long pumpIntervalCount;
    private long totalPumpIntervalNanos;
    private long maxPumpIntervalNanos;
    private long totalLoopDurationNanos;
    private long maxLoopDurationNanos;
    private long advancedTickCount;
    private long catchUpTickCount;
    private long multiTickPumpCount;
    private long s12Count;
    private long totalS12QueueNanos;
    private long maxS12QueueNanos;
    private double lastVelocityX;
    private double lastVelocityY;
    private double lastVelocityZ;
    private String pendingTimingAnomaly;

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

    boolean isRequestAccepted() {
        return requestAccepted;
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
        this.requestAccepted = true;
        this.duelStarted = true;
        this.duelStartedAtMillis = System.currentTimeMillis();
        this.expectedTargetUuid = targetUuid;
    }

    synchronized void markRequestAccepted() {
        this.requestAccepted = true;
    }

    void noteDisconnect(String disconnectReason) {
        this.lastDisconnectReason = disconnectReason == null ? "" : disconnectReason;
    }

    synchronized void recordTimingEvent(
            String event,
            long queuedNanos,
            double velocityX,
            double velocityY,
            double velocityZ
    ) {
        if (!"S12_PROCESSED".equals(event)) {
            return;
        }

        ++s12Count;
        totalS12QueueNanos += Math.max(0L, queuedNanos);
        maxS12QueueNanos = Math.max(maxS12QueueNanos, queuedNanos);
        lastVelocityX = velocityX;
        lastVelocityY = velocityY;
        lastVelocityZ = velocityZ;
        if (queuedNanos > SLOW_S12_QUEUE_NANOS) {
            pendingTimingAnomaly = "S12 queue=" + formatMillis(queuedNanos)
                    + "ms, velocity=" + formatVector(velocityX, velocityY, velocityZ);
        }
    }

    synchronized TimingLog recordGameLoop(long startedAtNanos, long completedAtNanos, int advancedTicks) {
        if (timingWindowStartedAtNanos == 0L) {
            timingWindowStartedAtNanos = startedAtNanos;
        }

        long pumpIntervalNanos = previousPumpStartedAtNanos == 0L
                ? 0L
                : Math.max(0L, startedAtNanos - previousPumpStartedAtNanos);
        previousPumpStartedAtNanos = startedAtNanos;
        long durationNanos = Math.max(0L, completedAtNanos - startedAtNanos);

        ++pumpCount;
        if (pumpIntervalNanos > 0L) {
            ++pumpIntervalCount;
            totalPumpIntervalNanos += pumpIntervalNanos;
            maxPumpIntervalNanos = Math.max(maxPumpIntervalNanos, pumpIntervalNanos);
        }
        totalLoopDurationNanos += durationNanos;
        maxLoopDurationNanos = Math.max(maxLoopDurationNanos, durationNanos);
        advancedTickCount += Math.max(0, advancedTicks);
        if (advancedTicks > 1) {
            ++multiTickPumpCount;
            catchUpTickCount += advancedTicks - 1L;
        }

        List<String> anomalies = new ArrayList<>(4);
        if (pumpIntervalNanos > SLOW_LOOP_NANOS) {
            anomalies.add("pump interval=" + formatMillis(pumpIntervalNanos) + "ms");
        }
        if (durationNanos > SLOW_LOOP_NANOS) {
            anomalies.add("loop duration=" + formatMillis(durationNanos) + "ms");
        }
        if (advancedTicks > 1) {
            anomalies.add("advancedTicks=" + advancedTicks);
        }
        if (pendingTimingAnomaly != null) {
            anomalies.add(pendingTimingAnomaly);
            pendingTimingAnomaly = null;
        }

        String anomaly = null;
        if (!anomalies.isEmpty()
                && (lastTimingAnomalyAtNanos == 0L
                        || completedAtNanos - lastTimingAnomalyAtNanos >= TIMING_ANOMALY_COOLDOWN_NANOS)) {
            lastTimingAnomalyAtNanos = completedAtNanos;
            anomaly = baseFields() + ", " + String.join(", ", anomalies);
        }

        String summary = null;
        long windowNanos = completedAtNanos - timingWindowStartedAtNanos;
        if (windowNanos >= TIMING_SUMMARY_INTERVAL_NANOS) {
            summary = timingSummary(windowNanos);
            resetTimingWindow(completedAtNanos);
        }
        return new TimingLog(anomaly, summary);
    }

    synchronized void noteTargetSeen(int entityId) {
        long now = System.currentTimeMillis();
        this.expectedTargetEntityId = entityId;
        this.lastTargetSeenAtMillis = now;
        this.consecutiveMissingTargetTicks = 0;
        this.targetWarningLoggedForCurrentMissingEpisode = false;
        this.repairRequestedForCurrentMissingEpisode = false;
    }

    synchronized int noteTargetMissingTicks(int ticks) {
        consecutiveMissingTargetTicks += Math.max(0, ticks);
        return consecutiveMissingTargetTicks;
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
        return !requestAccepted
                && !duelStarted
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
                + ", requestAccepted=" + requestAccepted
                + ", duelStarted=" + duelStarted
                + ", createdAtMillis=" + createdAtMillis
                + ", duelStartedAtMillis=" + duelStartedAtMillis;
    }

    private String timingSummary(long windowNanos) {
        double windowSeconds = windowNanos / 1_000_000_000.0D;
        return baseFields()
                + ", windowSeconds=" + formatDecimal(windowSeconds)
                + ", pumps=" + pumpCount
                + ", avgPumpIntervalMs=" + formatMillis(
                        pumpIntervalCount == 0L ? 0L : totalPumpIntervalNanos / pumpIntervalCount)
                + ", maxPumpIntervalMs=" + formatMillis(maxPumpIntervalNanos)
                + ", avgLoopDurationMs=" + formatMillis(
                        pumpCount == 0L ? 0L : totalLoopDurationNanos / pumpCount)
                + ", maxLoopDurationMs=" + formatMillis(maxLoopDurationNanos)
                + ", logicalTicks=" + advancedTickCount
                + ", tps=" + formatDecimal(windowSeconds <= 0.0D ? 0.0D : advancedTickCount / windowSeconds)
                + ", multiTickPumps=" + multiTickPumpCount
                + ", catchUpTicks=" + catchUpTickCount
                + ", s12Count=" + s12Count
                + ", avgS12QueueMs=" + formatMillis(s12Count == 0L ? 0L : totalS12QueueNanos / s12Count)
                + ", maxS12QueueMs=" + formatMillis(maxS12QueueNanos)
                + ", lastVelocity=" + formatVector(lastVelocityX, lastVelocityY, lastVelocityZ);
    }

    private void resetTimingWindow(long nowNanos) {
        timingWindowStartedAtNanos = nowNanos;
        pumpCount = 0L;
        pumpIntervalCount = 0L;
        totalPumpIntervalNanos = 0L;
        maxPumpIntervalNanos = 0L;
        totalLoopDurationNanos = 0L;
        maxLoopDurationNanos = 0L;
        advancedTickCount = 0L;
        catchUpTickCount = 0L;
        multiTickPumpCount = 0L;
        s12Count = 0L;
        totalS12QueueNanos = 0L;
        maxS12QueueNanos = 0L;
        lastVelocityX = 0.0D;
        lastVelocityY = 0.0D;
        lastVelocityZ = 0.0D;
    }

    private String formatMillis(long nanos) {
        return formatDecimal(nanos / 1_000_000.0D);
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String formatVector(double x, double y, double z) {
        return "(" + formatDecimal(x) + "," + formatDecimal(y) + "," + formatDecimal(z) + ")";
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    record TimingLog(String anomaly, String summary) {
    }
}
