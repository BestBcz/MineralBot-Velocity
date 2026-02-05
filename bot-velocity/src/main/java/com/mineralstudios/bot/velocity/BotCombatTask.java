package com.mineralstudios.bot.velocity;

import com.mineralstudios.bot.velocity.perception.BotPerception;
import com.mineralstudios.bot.velocity.perception.BotPerception.CombatState;
import com.mineralstudios.bot.velocity.perception.BotPerception.TrackedEntity;
import com.mineralstudios.bot.velocity.perception.BotPerception.ItemSlot;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bot Combat Task - Asynchronous AI decision loop for bot combat.
 * 
 * This class implements the combat AI by:
 * 1. Reading perception data (health, target position, inventory)
 * 2. Making decisions (attack, heal, move)
 * 3. Executing actions via PacketSenderUtils
 * 
 * Combat mechanics:
 * - Attack when target is within 4 blocks
 * - Heal when health <= 8 (4 hearts)
 * - Sprint reset (W-tap) on successful hits
 * - Strafe during combat
 */
public class BotCombatTask {
    
    private final Object plugin;
    private final ProxyServer server;
    private final Logger logger;
    
    // ========== Configuration ==========
    private static final double ATTACK_RANGE = 4.0;
    private static final double CHASE_RANGE = 16.0;
    private static final float HEAL_THRESHOLD = 8.0f; // 4 hearts
    private static final float CRITICAL_HEAL_THRESHOLD = 4.0f; // 2 hearts
    private static final long ATTACK_COOLDOWN_MS = 50; // ~20 CPS max
    private static final long HEAL_COOLDOWN_MS = 500; // Pot drinking time
    private static final int TICK_INTERVAL_MS = 50; // 20 TPS
    
    // ========== Item IDs (1.7.10) ==========
    private static final int ITEM_DIAMOND_SWORD = 276;
    private static final int ITEM_IRON_SWORD = 267;
    private static final int ITEM_GOLDEN_APPLE = 322;
    private static final int ITEM_SPLASH_POTION = 438;
    private static final int ITEM_POTION = 373;
    private static final int ITEM_ENDER_PEARL = 368;
    private static final int ITEM_MUSHROOM_STEW = 282;
    
    // ========== Potion Damage Values (1.7.10) ==========
    private static final int POTION_INSTANT_HEALTH_SPLASH = 16421; // Instant Health II Splash
    private static final int POTION_INSTANT_HEALTH = 8229; // Instant Health II Drink
    
    // ========== State Maps ==========
    private final Map<UUID, ScheduledTask> botTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BotCombatState> combatStates = new ConcurrentHashMap<>();
    
    // ========== Dependencies ==========
    private final BotPacketListener packetListener;
    
    public BotCombatTask(Object plugin, ProxyServer server, Logger logger, BotPacketListener packetListener) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.packetListener = packetListener;
    }
    
    /**
     * Internal state for each bot's combat AI.
     */
    private static class BotCombatState {
        UUID botUuid;
        String kitType;
        long lastAttackTime = 0;
        long lastHealTime = 0;
        long lastSwitchTime = 0;
        int originalSlot = 0;
        boolean isHealing = false;
        boolean isSprinting = true;
        int wTapPhase = 0; // 0=normal, 1=releasing W, 2=pressing W again
        long wTapStartTime = 0;
        
        // Aim smoothing
        float currentYaw = 0;
        float currentPitch = 0;
        float targetYaw = 0;
        float targetPitch = 0;
        
        // Strafe
        int strafeDirection = 0; // -1=left, 0=none, 1=right
        long lastStrafeChange = 0;
    }
    
    /**
     * Start combat task for a bot.
     */
    public void startCombatTask(UUID botUuid, UUID targetPlayerUuid, String kitType) {
        // Stop existing task if any
        stopCombatTask(botUuid);
        
        // Initialize combat state
        BotCombatState state = new BotCombatState();
        state.botUuid = botUuid;
        state.kitType = kitType;
        combatStates.put(botUuid, state);
        
        // Register perception
        BotPerception perception = packetListener.getPerception(botUuid);
        if (perception == null) {
            perception = packetListener.registerBot(botUuid);
        }
        
        // Set target (we'll find it by proximity since we may not know entity ID yet)
        // The actual targeting happens in the tick loop
        
        logger.info("Starting combat task for bot {} with kit {}", botUuid, kitType);
        
        // Schedule recurring task
        ScheduledTask task = server.getScheduler()
            .buildTask(plugin, () -> combatTick(botUuid))
            .repeat(TICK_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .schedule();
        
        botTasks.put(botUuid, task);
    }
    
    /**
     * Stop combat task for a bot.
     */
    public void stopCombatTask(UUID botUuid) {
        ScheduledTask task = botTasks.remove(botUuid);
        if (task != null) {
            task.cancel();
        }
        combatStates.remove(botUuid);
        packetListener.unregisterBot(botUuid);
        logger.info("Stopped combat task for bot {}", botUuid);
    }
    
    /**
     * Main combat tick - called every 50ms.
     */
    private void combatTick(UUID botUuid) {
        try {
            BotPerception perception = packetListener.getPerception(botUuid);
            BotCombatState state = combatStates.get(botUuid);
            
            if (perception == null || state == null) {
                return;
            }
            
            // AI Decision Tree
            // Priority 1: Critical heal (health <= 4)
            if (perception.isCriticalHealth() && canHeal(perception, state)) {
                executeHeal(perception, state);
                return;
            }
            
            // Priority 2: Find and track target
            TrackedEntity target = findAndTrackTarget(perception, state);
            if (target == null) {
                // No target - idle behavior
                executeIdle(perception, state);
                return;
            }
            
            double distance = perception.distanceTo(target.getEntityId());
            
            // Priority 3: Low health heal (if not in immediate danger)
            if (perception.isLowHealth() && distance > ATTACK_RANGE && canHeal(perception, state)) {
                executeHeal(perception, state);
                return;
            }
            
            // Priority 4: Combat logic based on distance
            if (distance <= ATTACK_RANGE) {
                executeCombat(perception, state, target, distance);
            } else if (distance <= CHASE_RANGE) {
                executeChase(perception, state, target, distance);
            } else {
                // Target too far
                executeIdle(perception, state);
            }
            
        } catch (Exception e) {
            logger.error("Error in combat tick for bot {}: {}", botUuid, e.getMessage());
        }
    }
    
    /**
     * Find and track the closest enemy player.
     */
    private TrackedEntity findAndTrackTarget(BotPerception perception, BotCombatState state) {
        // First check if we have a current target that's still valid
        int currentTargetId = perception.getTargetEntityId();
        if (currentTargetId >= 0) {
            TrackedEntity current = perception.getTrackedEntity(currentTargetId);
            if (current != null && current.isPlayer()) {
                double dist = perception.distanceTo(currentTargetId);
                if (dist <= CHASE_RANGE) {
                    return current;
                }
            }
        }
        
        // Find new target - closest player within range
        TrackedEntity closest = perception.getClosestPlayer(CHASE_RANGE);
        if (closest != null) {
            perception.setTargetEntityId(closest.getEntityId());
            logger.debug("Bot {} acquired new target: {}", state.botUuid, closest.getEntityId());
        } else {
            perception.setTargetEntityId(-1);
        }
        
        return closest;
    }
    
    /**
     * Execute combat when in attack range.
     */
    private void executeCombat(BotPerception perception, BotCombatState state, 
                               TrackedEntity target, double distance) {
        long now = System.currentTimeMillis();
        
        // Ensure we're holding a weapon
        ensureHoldingWeapon(perception, state);
        
        // Aim at target
        aimAtTarget(perception, state, target);
        
        // Handle W-tap (sprint reset)
        handleWTap(perception, state);
        
        // Handle strafing
        handleStrafe(perception, state, target);
        
        // Attack if cooldown is ready
        if (now - state.lastAttackTime >= ATTACK_COOLDOWN_MS) {
            // Send attack packet
            PacketSenderUtils.sendUseEntity(getBotInstance(state.botUuid), target.getEntityId());
            state.lastAttackTime = now;
            
            // Trigger W-tap on successful attack
            if (state.wTapPhase == 0) {
                startWTap(state);
            }
            
            logger.debug("Bot {} attacked target {} (dist={})", 
                    state.botUuid, target.getEntityId(), distance);
        }
        
        // Send movement packet
        sendMovementPacket(perception, state, true);
    }
    
    /**
     * Execute chase behavior when target is out of attack range.
     */
    private void executeChase(BotPerception perception, BotCombatState state, 
                              TrackedEntity target, double distance) {
        // Ensure we're holding a weapon
        ensureHoldingWeapon(perception, state);
        
        // Aim at target
        aimAtTarget(perception, state, target);
        
        // Sprint towards target
        if (!state.isSprinting) {
            state.isSprinting = true;
        }
        
        // Send movement (forward + sprint)
        sendMovementPacket(perception, state, true);
    }
    
    /**
     * Execute idle behavior when no target.
     */
    private void executeIdle(BotPerception perception, BotCombatState state) {
        state.wTapPhase = 0;
        state.strafeDirection = 0;
        
        // Just send basic position update
        sendMovementPacket(perception, state, false);
    }
    
    /**
     * Execute healing behavior.
     */
    private void executeHeal(BotPerception perception, BotCombatState state) {
        long now = System.currentTimeMillis();
        
        if (state.isHealing) {
            // Already healing - wait for completion
            if (now - state.lastHealTime >= HEAL_COOLDOWN_MS) {
                // Healing complete - switch back to weapon
                state.isHealing = false;
                switchToSlot(state, state.originalSlot);
                logger.debug("Bot {} finished healing", state.botUuid);
            }
            return;
        }
        
        // Find healing item based on kit type
        int healSlot = findHealingItem(perception, state);
        if (healSlot < 0) {
            logger.debug("Bot {} has no healing items", state.botUuid);
            return;
        }
        
        // Save current slot and switch to healing item
        state.originalSlot = perception.getSelectedSlot();
        switchToSlot(state, healSlot);
        
        // Use the item (right click)
        PacketSenderUtils.sendRightClick(getBotInstance(state.botUuid));
        
        state.isHealing = true;
        state.lastHealTime = now;
        
        logger.debug("Bot {} using healing item from slot {}", state.botUuid, healSlot);
    }
    
    /**
     * Check if bot can heal (has items and cooldown ready).
     */
    private boolean canHeal(BotPerception perception, BotCombatState state) {
        if (state.isHealing) return false;
        
        long now = System.currentTimeMillis();
        if (now - state.lastHealTime < HEAL_COOLDOWN_MS) return false;
        
        return findHealingItem(perception, state) >= 0;
    }
    
    /**
     * Find appropriate healing item based on kit type.
     */
    private int findHealingItem(BotPerception perception, BotCombatState state) {
        String kit = state.kitType.toLowerCase();
        
        // Check based on kit type
        if (kit.contains("soup")) {
            // Soup kit - look for mushroom stew
            return perception.findHotbarSlot(ITEM_MUSHROOM_STEW);
        } else if (kit.contains("gapple") || kit.contains("uhc")) {
            // Gapple kit - look for golden apples
            return perception.findHotbarSlot(ITEM_GOLDEN_APPLE);
        } else {
            // Default (NoDebuff etc) - look for splash potions first, then regular potions
            int slot = perception.findHotbarSlot(ITEM_SPLASH_POTION);
            if (slot >= 0) return slot;
            return perception.findHotbarSlot(ITEM_POTION);
        }
    }
    
    /**
     * Find best weapon in hotbar.
     */
    private int findWeaponSlot(BotPerception perception) {
        // Priority: Diamond > Iron > Gold > Stone > Wood sword
        int[] swordPriority = {276, 267, 283, 272, 268};
        
        for (int swordId : swordPriority) {
            int slot = perception.findHotbarSlot(swordId);
            if (slot >= 0) return slot;
        }
        
        // No sword found - find any weapon-like item or default to slot 0
        return 0;
    }
    
    /**
     * Ensure bot is holding a weapon.
     */
    private void ensureHoldingWeapon(BotPerception perception, BotCombatState state) {
        if (state.isHealing) return; // Don't interrupt healing
        
        int weaponSlot = findWeaponSlot(perception);
        if (perception.getSelectedSlot() != weaponSlot) {
            switchToSlot(state, weaponSlot);
        }
    }
    
    /**
     * Switch to a hotbar slot.
     */
    private void switchToSlot(BotCombatState state, int slot) {
        long now = System.currentTimeMillis();
        if (now - state.lastSwitchTime < 50) return; // Rate limit slot switches
        
        PacketSenderUtils.sendSwitchItem(getBotInstance(state.botUuid), slot);
        state.lastSwitchTime = now;
    }
    
    /**
     * Calculate and apply aim towards target.
     */
    private void aimAtTarget(BotPerception perception, BotCombatState state, TrackedEntity target) {
        double dx = target.getX() - perception.getPosX();
        double dy = (target.getY() + 1.62) - (perception.getPosY() + 1.62); // Eye height
        double dz = target.getZ() - perception.getPosZ();
        
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        
        // Calculate target angles
        state.targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        state.targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        
        // Clamp pitch
        state.targetPitch = Math.max(-90, Math.min(90, state.targetPitch));
        
        // Smooth aim transition
        float yawDiff = angleDifference(state.currentYaw, state.targetYaw);
        float pitchDiff = state.targetPitch - state.currentPitch;
        
        // Aim speed (degrees per tick) - adjust for difficulty
        float aimSpeed = 15.0f;
        
        if (Math.abs(yawDiff) > aimSpeed) {
            state.currentYaw += Math.signum(yawDiff) * aimSpeed;
        } else {
            state.currentYaw = state.targetYaw;
        }
        
        if (Math.abs(pitchDiff) > aimSpeed) {
            state.currentPitch += Math.signum(pitchDiff) * aimSpeed;
        } else {
            state.currentPitch = state.targetPitch;
        }
        
        // Normalize yaw
        while (state.currentYaw > 180) state.currentYaw -= 360;
        while (state.currentYaw < -180) state.currentYaw += 360;
    }
    
    /**
     * Calculate angle difference (handles wrapping).
     */
    private float angleDifference(float current, float target) {
        float diff = target - current;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return diff;
    }
    
    /**
     * Handle W-tap sprint reset mechanic.
     */
    private void handleWTap(BotPerception perception, BotCombatState state) {
        long now = System.currentTimeMillis();
        
        switch (state.wTapPhase) {
            case 1: // Releasing W
                if (now - state.wTapStartTime >= 50) { // 50ms without W
                    state.wTapPhase = 2;
                    state.wTapStartTime = now;
                }
                break;
            case 2: // Pressing W again
                if (now - state.wTapStartTime >= 50) {
                    state.wTapPhase = 0; // Done
                }
                break;
        }
    }
    
    /**
     * Start W-tap sequence.
     */
    private void startWTap(BotCombatState state) {
        state.wTapPhase = 1;
        state.wTapStartTime = System.currentTimeMillis();
    }
    
    /**
     * Handle strafing during combat.
     */
    private void handleStrafe(BotPerception perception, BotCombatState state, TrackedEntity target) {
        long now = System.currentTimeMillis();
        
        // Change strafe direction periodically
        if (now - state.lastStrafeChange > 500) { // Change every 500ms
            state.strafeDirection = (state.strafeDirection == 0) ? 
                (Math.random() > 0.5 ? 1 : -1) : -state.strafeDirection;
            state.lastStrafeChange = now;
        }
    }
    
    /**
     * Send movement packet with current state.
     */
    private void sendMovementPacket(BotPerception perception, BotCombatState state, boolean moving) {
        if (!moving) {
            // Just send flying packet (keep-alive equivalent)
            PacketSenderUtils.sendFlying(getBotInstance(state.botUuid), perception.isOnGround());
            return;
        }
        
        // Calculate movement direction
        float moveYaw = state.currentYaw;
        boolean forward = state.wTapPhase != 1; // Not pressing W during W-tap release phase
        
        // Apply strafe offset
        if (state.strafeDirection != 0) {
            moveYaw += state.strafeDirection * 45; // Strafe at 45 degrees
        }
        
        // Calculate velocity (simplified - in a real implementation you'd track exact position)
        double speed = 0.1; // Blocks per tick while sprinting
        if (!forward) speed = 0;
        
        double moveX = -Math.sin(Math.toRadians(moveYaw)) * speed;
        double moveZ = Math.cos(Math.toRadians(moveYaw)) * speed;
        
        // New position
        double newX = perception.getPosX() + moveX;
        double newY = perception.getPosY();
        double newZ = perception.getPosZ() + moveZ;
        
        // Send position and look packet
        PacketSenderUtils.sendPositionAndRotation(
            getBotInstance(state.botUuid),
            newX, newY, newZ,
            state.currentYaw, state.currentPitch,
            perception.isOnGround()
        );
    }
    
    /**
     * Get ClientInstance for a bot UUID.
     * Uses the resolver injected by VelocityBotManager.
     */
    private gg.mineral.bot.base.client.instance.ClientInstance getBotInstance(UUID botUuid) {
        if (botInstanceResolver != null) {
            return botInstanceResolver.apply(botUuid);
        }
        logger.warn("Bot instance resolver not set - cannot get bot instance for {}", botUuid);
        return null;
    }
    
    /**
     * Set the bot instance resolver (called by VelocityBotManager).
     */
    private java.util.function.Function<UUID, gg.mineral.bot.base.client.instance.ClientInstance> botInstanceResolver;
    
    public void setBotInstanceResolver(
            java.util.function.Function<UUID, gg.mineral.bot.base.client.instance.ClientInstance> resolver) {
        this.botInstanceResolver = resolver;
    }
    
    /**
     * Get bot instance using resolver.
     */
    private gg.mineral.bot.base.client.instance.ClientInstance resolveBotInstance(UUID botUuid) {
        if (botInstanceResolver != null) {
            return botInstanceResolver.apply(botUuid);
        }
        return null;
    }
}
