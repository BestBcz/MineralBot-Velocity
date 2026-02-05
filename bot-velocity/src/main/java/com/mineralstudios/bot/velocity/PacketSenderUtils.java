package com.mineralstudios.bot.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import gg.mineral.bot.base.client.instance.ClientInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * Packet Sender Utils - Utility class for sending 1.7.10 compatible client packets.
 * 
 * This class wraps PacketEvents to send client-to-server packets that simulate
 * player actions such as:
 * - Movement (position, rotation)
 * - Combat (attack entity, use item)
 * - Inventory (slot change)
 * - Actions (sprint, sneak, swing arm)
 */
public class PacketSenderUtils {
    
    private static final Logger logger = LoggerFactory.getLogger(PacketSenderUtils.class);

    // ========== Core Packet Sending ==========
    
    /**
     * Send a packet from a bot to the server.
     * 
     * @param bot The bot's ClientInstance
     * @param wrapper The packet wrapper to send
     */
    public static void sendPacket(ClientInstance bot, PacketWrapper<?> wrapper) {
        if (bot == null) {
            logger.warn("Cannot send packet - bot is null");
            return;
        }
        
        User user = PacketEvents.getAPI().getProtocolManager().getUser(bot.getConfiguration().getUuid());
        if (user != null) {
            user.sendPacket(wrapper);
        } else {
            logger.warn("Cannot send packet - user not found for bot {}", bot.getConfiguration().getUuid());
        }
    }
    
    /**
     * Send a packet using bot UUID directly.
     * 
     * @param botUuid The bot's UUID
     * @param wrapper The packet wrapper to send
     */
    public static void sendPacketByUuid(UUID botUuid, PacketWrapper<?> wrapper) {
        if (botUuid == null) {
            logger.warn("Cannot send packet - botUuid is null");
            return;
        }
        
        User user = PacketEvents.getAPI().getProtocolManager().getUser(botUuid);
        if (user != null) {
            user.sendPacket(wrapper);
        } else {
            logger.warn("Cannot send packet - user not found for UUID {}", botUuid);
        }
    }

    // ========== Movement Packets ==========
    
    /**
     * Send position and rotation update.
     * Equivalent to PacketPlayInPositionLook (C04)
     */
    public static void sendPositionAndRotation(ClientInstance bot, double x, double y, double z, 
                                                float yaw, float pitch, boolean onGround) {
        WrapperPlayClientPlayerPositionAndRotation packet = new WrapperPlayClientPlayerPositionAndRotation(
                new Location(x, y, z, yaw, pitch), onGround);
        sendPacket(bot, packet);
    }
    
    /**
     * Send position and rotation update by UUID.
     */
    public static void sendPositionAndRotation(UUID botUuid, double x, double y, double z, 
                                                float yaw, float pitch, boolean onGround) {
        WrapperPlayClientPlayerPositionAndRotation packet = new WrapperPlayClientPlayerPositionAndRotation(
                new Location(x, y, z, yaw, pitch), onGround);
        sendPacketByUuid(botUuid, packet);
    }
    
    /**
     * Send position only update (no rotation).
     * Equivalent to PacketPlayInPosition (C03)
     */
    public static void sendPosition(ClientInstance bot, double x, double y, double z, boolean onGround) {
        WrapperPlayClientPlayerPosition packet = new WrapperPlayClientPlayerPosition(
                new Vector3d(x, y, z), onGround);
        sendPacket(bot, packet);
    }
    
    /**
     * Send position only update by UUID.
     */
    public static void sendPosition(UUID botUuid, double x, double y, double z, boolean onGround) {
        WrapperPlayClientPlayerPosition packet = new WrapperPlayClientPlayerPosition(
                new Vector3d(x, y, z), onGround);
        sendPacketByUuid(botUuid, packet);
    }
    
    /**
     * Send rotation only update.
     * Equivalent to PacketPlayInLook (C05)
     */
    public static void sendRotation(ClientInstance bot, float yaw, float pitch, boolean onGround) {
        WrapperPlayClientPlayerRotation packet = new WrapperPlayClientPlayerRotation(yaw, pitch, onGround);
        sendPacket(bot, packet);
    }
    
    /**
     * Send rotation only update by UUID.
     */
    public static void sendRotation(UUID botUuid, float yaw, float pitch, boolean onGround) {
        WrapperPlayClientPlayerRotation packet = new WrapperPlayClientPlayerRotation(yaw, pitch, onGround);
        sendPacketByUuid(botUuid, packet);
    }

    /**
     * Send flying packet (keep-alive / ground state update).
     * Equivalent to PacketPlayInFlying (C03) with no position
     */
    public static void sendFlying(ClientInstance bot, boolean onGround) {
        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(
                false, false, onGround, new Location(0, 0, 0, 0, 0));
        sendPacket(bot, packet);
    }
    
    /**
     * Send flying packet by UUID.
     */
    public static void sendFlying(UUID botUuid, boolean onGround) {
        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(
                false, false, onGround, new Location(0, 0, 0, 0, 0));
        sendPacketByUuid(botUuid, packet);
    }

    // ========== Combat Packets ==========
    
    /**
     * Send attack entity packet.
     * Equivalent to PacketPlayInUseEntity with ATTACK action.
     * 
     * @param bot The bot's ClientInstance
     * @param targetId The entity ID to attack
     */
    public static void sendUseEntity(ClientInstance bot, int targetId) {
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(
                targetId,
                WrapperPlayClientInteractEntity.InteractAction.ATTACK,
                InteractionHand.MAIN_HAND,
                Optional.empty(),
                Optional.empty());
        sendPacket(bot, packet);
    }
    
    /**
     * Send attack entity packet by UUID.
     */
    public static void sendAttack(UUID botUuid, int targetId) {
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(
                targetId,
                WrapperPlayClientInteractEntity.InteractAction.ATTACK,
                InteractionHand.MAIN_HAND,
                Optional.empty(),
                Optional.empty());
        sendPacketByUuid(botUuid, packet);
    }
    
    /**
     * Send interact entity packet (right-click on entity).
     * 
     * @param bot The bot's ClientInstance
     * @param targetId The entity ID to interact with
     */
    public static void sendInteractEntity(ClientInstance bot, int targetId) {
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(
                targetId,
                WrapperPlayClientInteractEntity.InteractAction.INTERACT,
                InteractionHand.MAIN_HAND,
                Optional.empty(),
                Optional.empty());
        sendPacket(bot, packet);
    }
    
    /**
     * Send arm swing animation.
     * Equivalent to PacketPlayInArmAnimation
     */
    public static void sendSwingArm(ClientInstance bot) {
        WrapperPlayClientAnimation packet = new WrapperPlayClientAnimation(InteractionHand.MAIN_HAND);
        sendPacket(bot, packet);
    }
    
    /**
     * Send arm swing animation by UUID.
     */
    public static void sendSwingArm(UUID botUuid) {
        WrapperPlayClientAnimation packet = new WrapperPlayClientAnimation(InteractionHand.MAIN_HAND);
        sendPacketByUuid(botUuid, packet);
    }

    // ========== Inventory Packets ==========
    
    /**
     * Send held item change (switch hotbar slot).
     * Equivalent to PacketPlayInHeldItemSlot
     * 
     * @param bot The bot's ClientInstance
     * @param slot The hotbar slot (0-8)
     */
    public static void sendSwitchItem(ClientInstance bot, int slot) {
        if (slot < 0 || slot > 8) {
            logger.warn("Invalid hotbar slot: {}", slot);
            return;
        }
        WrapperPlayClientHeldItemChange packet = new WrapperPlayClientHeldItemChange(slot);
        sendPacket(bot, packet);
    }
    
    /**
     * Send held item change by UUID.
     */
    public static void sendSwitchItem(UUID botUuid, int slot) {
        if (slot < 0 || slot > 8) {
            logger.warn("Invalid hotbar slot: {}", slot);
            return;
        }
        WrapperPlayClientHeldItemChange packet = new WrapperPlayClientHeldItemChange(slot);
        sendPacketByUuid(botUuid, packet);
    }

    /**
     * Send right-click (use item) packet.
     * For 1.7.10, this uses the block placement packet with special coordinates.
     * Equivalent to using an item like food, potions, etc.
     */
    public static void sendRightClick(ClientInstance bot) {
        // In 1.7.10, right-click use item is block placement with x=-1, y=255, z=-1
        // Constructor: (InteractionHand, Vector3i, BlockFace, Vector3f, ItemStack, Boolean insideBlock, int sequence)
        WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(
                InteractionHand.MAIN_HAND,
                new Vector3i(-1, 255, -1),
                BlockFace.UP,
                new Vector3f(0f, 0f, 0f),
                ItemStack.EMPTY,
                false,
                0);
        sendPacket(bot, packet);
    }
    
    /**
     * Send right-click by UUID.
     */
    public static void sendRightClick(UUID botUuid) {
        WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(
                InteractionHand.MAIN_HAND,
                new Vector3i(-1, 255, -1),
                BlockFace.UP,
                new Vector3f(0f, 0f, 0f),
                ItemStack.EMPTY,
                false,
                0);
        sendPacketByUuid(botUuid, packet);
    }
    
    /**
     * Send block placement at a specific location.
     */
    public static void sendBlockPlace(ClientInstance bot, int x, int y, int z, BlockFace face) {
        WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(
                InteractionHand.MAIN_HAND,
                new Vector3i(x, y, z),
                face,
                new Vector3f(0.5f, 0.5f, 0.5f),
                ItemStack.EMPTY,
                false,
                0);
        sendPacket(bot, packet);
    }

    // ========== Action Packets ==========
    
    /**
     * Send entity action (sprint, sneak, etc).
     * Equivalent to PacketPlayInEntityAction
     * 
     * @param bot The bot's ClientInstance
     * @param action The action to perform
     */
    public static void sendEntityAction(ClientInstance bot, WrapperPlayClientEntityAction.Action action) {
        // Constructor: (int entityId, Action action, int jumpBoost)
        WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(
                0, // Entity ID - will be filled by the server or we use the bot's entity ID
                action,
                0); // Jump boost (usually 0)
        sendPacket(bot, packet);
    }
    
    /**
     * Start sprinting.
     */
    public static void sendStartSprint(ClientInstance bot) {
        sendEntityAction(bot, WrapperPlayClientEntityAction.Action.START_SPRINTING);
    }
    
    /**
     * Start sprinting by UUID.
     */
    public static void sendStartSprint(UUID botUuid) {
        WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(
                0, WrapperPlayClientEntityAction.Action.START_SPRINTING, 0);
        sendPacketByUuid(botUuid, packet);
    }
    
    /**
     * Stop sprinting.
     */
    public static void sendStopSprint(ClientInstance bot) {
        sendEntityAction(bot, WrapperPlayClientEntityAction.Action.STOP_SPRINTING);
    }
    
    /**
     * Stop sprinting by UUID.
     */
    public static void sendStopSprint(UUID botUuid) {
        WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(
                0, WrapperPlayClientEntityAction.Action.STOP_SPRINTING, 0);
        sendPacketByUuid(botUuid, packet);
    }
    
    /**
     * Start sneaking.
     */
    public static void sendStartSneak(ClientInstance bot) {
        sendEntityAction(bot, WrapperPlayClientEntityAction.Action.START_SNEAKING);
    }
    
    /**
     * Stop sneaking.
     */
    public static void sendStopSneak(ClientInstance bot) {
        sendEntityAction(bot, WrapperPlayClientEntityAction.Action.STOP_SNEAKING);
    }

    // ========== Utility Methods ==========
    
    /**
     * Calculate yaw angle from direction vector.
     * 
     * @param dx X direction
     * @param dz Z direction
     * @return Yaw angle in degrees
     */
    public static float calculateYaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
    
    /**
     * Calculate pitch angle from direction vector.
     * 
     * @param dy Y direction
     * @param horizontalDist Horizontal distance
     * @return Pitch angle in degrees
     */
    public static float calculatePitch(double dy, double horizontalDist) {
        return (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));
    }
    
    /**
     * Calculate rotation angles to look at a target position.
     * 
     * @param fromX Source X
     * @param fromY Source Y (eye height)
     * @param fromZ Source Z
     * @param toX Target X
     * @param toY Target Y (eye height)
     * @param toZ Target Z
     * @return Array of [yaw, pitch]
     */
    public static float[] calculateLookAt(double fromX, double fromY, double fromZ,
                                           double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = calculateYaw(dx, dz);
        float pitch = calculatePitch(dy, horizontalDist);
        
        // Clamp pitch
        pitch = Math.max(-90, Math.min(90, pitch));
        
        return new float[] { yaw, pitch };
    }
    
    /**
     * Normalize yaw angle to -180 to 180 range.
     */
    public static float normalizeYaw(float yaw) {
        yaw = yaw % 360;
        if (yaw > 180) yaw -= 360;
        if (yaw < -180) yaw += 360;
        return yaw;
    }
    
    /**
     * Calculate angle difference (handles wrapping).
     */
    public static float angleDifference(float current, float target) {
        float diff = target - current;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return diff;
    }
}
