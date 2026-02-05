package com.mineralstudios.bot.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.mineralstudios.bot.velocity.perception.BotPerception;
import com.mineralstudios.bot.velocity.perception.BotPerception.ItemSlot;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Bot Packet Listener - Intercepts and parses server-to-client packets for perception.
 * 
 * This class handles the "perception" layer of the bot by:
 * 1. Entity Tracking: ENTITY_RELATIVE_MOVE, ENTITY_TELEPORT, SPAWN_PLAYER
 * 2. Health Updates: UPDATE_HEALTH
 * 3. Inventory Sync: WINDOW_ITEMS, SET_SLOT
 * 4. Position Updates: PLAYER_POSITION_AND_LOOK
 * 
 * All parsed data is stored in BotPerception for AI decision-making.
 */
public class BotPacketListener extends PacketListenerAbstract {
    
    private final Logger logger;
    
    /** Map of Bot UUID -> BotPerception */
    private final Map<UUID, BotPerception> botPerceptions = new ConcurrentHashMap<>();
    
    /** Map of Entity ID -> Bot UUID (for reverse lookup) */
    private final Map<Integer, UUID> entityIdToBotUuid = new ConcurrentHashMap<>();
    
    /** Map of Username -> Bot UUID (for matching network UUID to our UUID) */
    private final Map<String, UUID> usernameToUuid = new ConcurrentHashMap<>();
    
    /** Map of Network UUID (from PacketEvents) -> Our Bot UUID */
    private final Map<UUID, UUID> networkUuidToOurUuid = new ConcurrentHashMap<>();
    
    /** Debug mode - log all received packet types */
    private boolean debugMode = true;
    
    public BotPacketListener(Logger logger) {
        super(PacketListenerPriority.NORMAL);
        this.logger = logger;
    }
    
    /**
     * Register a bot for perception tracking.
     * @param botUuid Our internal bot UUID
     * @param username The bot's username (used for matching network connections)
     */
    public BotPerception registerBot(UUID botUuid, String username) {
        BotPerception perception = new BotPerception(botUuid);
        botPerceptions.put(botUuid, perception);
        if (username != null) {
            usernameToUuid.put(username, botUuid);
        }
        logger.info("Registered perception for bot: {} (username: {})", botUuid, username);
        return perception;
    }
    
    /**
     * Register a bot for perception tracking (legacy method).
     */
    public BotPerception registerBot(UUID botUuid) {
        return registerBot(botUuid, null);
    }
    
    /**
     * Map a network UUID to our internal bot UUID.
     * Call this when we discover the UUID that PacketEvents uses for a bot.
     */
    public void mapNetworkUuid(UUID networkUuid, UUID ourBotUuid) {
        networkUuidToOurUuid.put(networkUuid, ourBotUuid);
        logger.info("Mapped network UUID {} to our bot UUID {}", networkUuid, ourBotUuid);
    }
    
    /**
     * Map a network UUID by username.
     */
    public void mapNetworkUuidByUsername(UUID networkUuid, String username) {
        UUID ourUuid = usernameToUuid.get(username);
        if (ourUuid != null) {
            mapNetworkUuid(networkUuid, ourUuid);
        }
    }
    
    /**
     * Unregister a bot from perception tracking.
     */
    public void unregisterBot(UUID botUuid) {
        BotPerception perception = botPerceptions.remove(botUuid);
        if (perception != null) {
            // Clean up entity ID mapping
            int entityId = perception.getEntityId();
            if (entityId >= 0) {
                entityIdToBotUuid.remove(entityId);
            }
            logger.info("Unregistered perception for bot: {}", botUuid);
        }
        
        // Clean up username mapping
        usernameToUuid.entrySet().removeIf(entry -> entry.getValue().equals(botUuid));
        
        // Clean up network UUID mapping
        networkUuidToOurUuid.entrySet().removeIf(entry -> entry.getValue().equals(botUuid));
    }
    
    /**
     * Get perception data for a bot (by our UUID).
     */
    public BotPerception getPerception(UUID botUuid) {
        return botPerceptions.get(botUuid);
    }
    
    /**
     * Get perception by network UUID (with fallback to our UUID).
     */
    private BotPerception getPerceptionByNetworkUuid(UUID networkUuid) {
        // First try direct lookup
        BotPerception perception = botPerceptions.get(networkUuid);
        if (perception != null) {
            return perception;
        }
        
        // Try mapped lookup
        UUID ourUuid = networkUuidToOurUuid.get(networkUuid);
        if (ourUuid != null) {
            return botPerceptions.get(ourUuid);
        }
        
        return null;
    }
    
    /**
     * Get perception by entity ID.
     */
    public BotPerception getPerceptionByEntityId(int entityId) {
        UUID botUuid = entityIdToBotUuid.get(entityId);
        return botUuid != null ? botPerceptions.get(botUuid) : null;
    }
    
    @Override
    public void onPacketSend(PacketSendEvent event) {
        // This handles packets being sent TO the client (bot)
        User user = event.getUser();
        if (user == null) return;
        
        UUID networkUuid = user.getUUID();
        String username = user.getName();
        
        if (networkUuid == null) return;
        
        // Try to find perception by network UUID or username
        BotPerception perception = getPerceptionByNetworkUuid(networkUuid);
        
        // If not found by UUID, try by username
        if (perception == null && username != null) {
            UUID ourUuid = usernameToUuid.get(username);
            if (ourUuid != null) {
                perception = botPerceptions.get(ourUuid);
                // Map this network UUID for future lookups
                if (perception != null) {
                    mapNetworkUuid(networkUuid, ourUuid);
                    logger.info("Auto-mapped network UUID {} to bot UUID {} via username {}", 
                            networkUuid, ourUuid, username);
                }
            }
        }
        
        if (perception == null) {
            // Not a tracked bot
            return;
        }
        
        // Debug: Log packet types for bots
        if (debugMode && event.getPacketType() != null) {
            PacketType.Play.Server packetType = (PacketType.Play.Server) event.getPacketType();
            if (packetType == PacketType.Play.Server.UPDATE_HEALTH ||
                packetType == PacketType.Play.Server.SPAWN_PLAYER ||
                packetType == PacketType.Play.Server.JOIN_GAME ||
                packetType == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
                logger.info("Bot {} received packet: {}", perception.getBotUuid(), packetType);
            }
        }
        
        try {
            handleServerPacket(event, perception);
        } catch (Exception e) {
            logger.error("Error handling packet for bot {}: {}", perception.getBotUuid(), e.getMessage(), e);
        }
    }
    
    private void handleServerPacket(PacketSendEvent event, BotPerception perception) {
        PacketType.Play.Server packetType = (PacketType.Play.Server) event.getPacketType();
        
        switch (packetType) {
            // ========== Health Updates ==========
            case UPDATE_HEALTH:
                handleUpdateHealth(event, perception);
                break;
                
            // ========== Entity Movement ==========
            case ENTITY_RELATIVE_MOVE:
                handleEntityRelativeMove(event, perception);
                break;
                
            case ENTITY_RELATIVE_MOVE_AND_ROTATION:
                handleEntityRelativeMoveAndRotation(event, perception);
                break;
                
            case ENTITY_TELEPORT:
                handleEntityTeleport(event, perception);
                break;
                
            // ========== Entity Spawning ==========
            case SPAWN_PLAYER:
                handleSpawnPlayer(event, perception);
                break;
                
            case DESTROY_ENTITIES:
                handleDestroyEntities(event, perception);
                break;
                
            // ========== Position Updates ==========
            case PLAYER_POSITION_AND_LOOK:
                handlePlayerPositionAndLook(event, perception);
                break;
                
            // ========== Inventory ==========
            case WINDOW_ITEMS:
                handleWindowItems(event, perception);
                break;
                
            case SET_SLOT:
                handleSetSlot(event, perception);
                break;
                
            case HELD_ITEM_CHANGE:
                handleHeldItemChange(event, perception);
                break;
                
            // ========== Join Game (for entity ID) ==========
            case JOIN_GAME:
                handleJoinGame(event, perception);
                break;
                
            // ========== Entity Status (hurt animation) ==========
            case ENTITY_STATUS:
                handleEntityStatus(event, perception);
                break;
                
            default:
                // Ignore other packets
                break;
        }
    }
    
    // ==================== Packet Handlers ====================
    
    /**
     * Handle UPDATE_HEALTH (0x06 in 1.7.10)
     * Updates bot's health, food, and saturation.
     */
    private void handleUpdateHealth(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerUpdateHealth packet = new WrapperPlayServerUpdateHealth(event);
        
        float health = packet.getHealth();
        int food = packet.getFood();
        float saturation = packet.getFoodSaturation();
        
        perception.updateHealth(health, food, saturation);
        
        logger.info("Bot {} health update: HP={}, Food={}, Sat={}", 
                perception.getBotUuid(), health, food, saturation);
        
        // Check for critical health condition
        if (perception.isLowHealth()) {
            logger.info("Bot {} is low on health! HP={}", perception.getBotUuid(), health);
        }
    }
    
    /**
     * Handle ENTITY_RELATIVE_MOVE (0x15 in 1.7.10)
     * Updates entity position relative to current position.
     */
    private void handleEntityRelativeMove(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerEntityRelativeMove packet = new WrapperPlayServerEntityRelativeMove(event);
        
        int entityId = packet.getEntityId();
        // In 1.7.10, delta values are in fixed-point format (1/32 of a block)
        double deltaX = packet.getDeltaX();
        double deltaY = packet.getDeltaY();
        double deltaZ = packet.getDeltaZ();
        
        perception.updateEntityPositionRelative(entityId, deltaX, deltaY, deltaZ);
    }
    
    /**
     * Handle ENTITY_RELATIVE_MOVE_AND_ROTATION
     */
    private void handleEntityRelativeMoveAndRotation(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerEntityRelativeMoveAndRotation packet = 
                new WrapperPlayServerEntityRelativeMoveAndRotation(event);
        
        int entityId = packet.getEntityId();
        double deltaX = packet.getDeltaX();
        double deltaY = packet.getDeltaY();
        double deltaZ = packet.getDeltaZ();
        
        perception.updateEntityPositionRelative(entityId, deltaX, deltaY, deltaZ);
        
        // Also update rotation if tracked
        BotPerception.TrackedEntity entity = perception.getTrackedEntity(entityId);
        if (entity != null) {
            entity.updateRotation(packet.getYaw(), packet.getPitch());
        }
    }
    
    /**
     * Handle ENTITY_TELEPORT (0x18 in 1.7.10)
     * Updates entity to absolute position.
     */
    private void handleEntityTeleport(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerEntityTeleport packet = new WrapperPlayServerEntityTeleport(event);
        
        int entityId = packet.getEntityId();
        double x = packet.getPosition().getX();
        double y = packet.getPosition().getY();
        double z = packet.getPosition().getZ();
        
        perception.updateEntityPosition(entityId, x, y, z);
        
        // Update rotation
        BotPerception.TrackedEntity entity = perception.getTrackedEntity(entityId);
        if (entity != null) {
            entity.updateRotation(packet.getYaw(), packet.getPitch());
        }
        
        logger.debug("Entity {} teleported to ({}, {}, {})", entityId, x, y, z);
    }
    
    /**
     * Handle SPAWN_PLAYER (0x0C in 1.7.10)
     * Adds a new player entity to tracking.
     */
    private void handleSpawnPlayer(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerSpawnPlayer packet = new WrapperPlayServerSpawnPlayer(event);
        
        int entityId = packet.getEntityId();
        UUID playerUuid = packet.getUUID();
        double x = packet.getPosition().getX();
        double y = packet.getPosition().getY();
        double z = packet.getPosition().getZ();
        float yaw = packet.getYaw();
        float pitch = packet.getPitch();
        
        // Create tracked entity
        perception.updateEntityPosition(entityId, x, y, z);
        BotPerception.TrackedEntity entity = perception.getTrackedEntity(entityId);
        if (entity != null) {
            entity.setPlayer(true);
            entity.setUuid(playerUuid);
            entity.updateRotation(yaw, pitch);
        }
        
        logger.info("Bot {} sees player spawn: entityId={}, uuid={}, pos=({}, {}, {})", 
                perception.getBotUuid(), entityId, playerUuid, x, y, z);
    }
    
    /**
     * Handle DESTROY_ENTITIES
     * Removes entities from tracking.
     */
    private void handleDestroyEntities(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerDestroyEntities packet = new WrapperPlayServerDestroyEntities(event);
        
        for (int entityId : packet.getEntityIds()) {
            perception.removeEntity(entityId);
            logger.debug("Entity {} destroyed", entityId);
        }
    }
    
    /**
     * Handle PLAYER_POSITION_AND_LOOK
     * Updates bot's own position (server correction).
     */
    private void handlePlayerPositionAndLook(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerPlayerPositionAndLook packet = 
                new WrapperPlayServerPlayerPositionAndLook(event);
        
        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        float yaw = packet.getYaw();
        float pitch = packet.getPitch();
        
        perception.updatePosition(x, y, z, yaw, pitch, perception.isOnGround());
        
        logger.info("Bot {} position update: ({}, {}, {}) yaw={} pitch={}", 
                perception.getBotUuid(), x, y, z, yaw, pitch);
    }
    
    /**
     * Handle WINDOW_ITEMS (0x30 in 1.7.10)
     * Full inventory sync.
     */
    private void handleWindowItems(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
        
        int windowId = packet.getWindowId();
        List<com.github.retrooper.packetevents.protocol.item.ItemStack> items = packet.getItems();
        
        // Window ID 0 is player inventory
        if (windowId == 0) {
            ItemSlot[] slots = new ItemSlot[items.size()];
            for (int i = 0; i < items.size(); i++) {
                slots[i] = new ItemSlot();
                com.github.retrooper.packetevents.protocol.item.ItemStack item = items.get(i);
                if (item != null && item.getType() != null && !item.isEmpty()) {
                    // Use getLegacyData() or getType().getName() for item identification
                    int itemId = item.getType().getId(event.getUser().getClientVersion());
                    slots[i].update(
                        itemId, 
                        item.getAmount(), 
                        item.getLegacyData() // Use legacy data for damage value
                    );
                }
            }
            perception.updateFullInventory(slots);
            
            logger.info("Bot {} inventory synced: {} slots", perception.getBotUuid(), items.size());
        }
    }
    
    /**
     * Handle SET_SLOT
     * Single slot update.
     */
    private void handleSetSlot(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
        
        int windowId = packet.getWindowId();
        int slot = packet.getSlot();
        com.github.retrooper.packetevents.protocol.item.ItemStack item = packet.getItem();
        
        // Window ID 0 is player inventory, -1 is cursor
        if (windowId == 0 || windowId == -2) { // -2 is player inventory shortcut
            if (item != null && item.getType() != null && !item.isEmpty()) {
                int itemId = item.getType().getId(event.getUser().getClientVersion());
                perception.updateInventorySlot(slot, itemId, item.getAmount(), item.getLegacyData());
            } else {
                perception.updateInventorySlot(slot, -1, 0, 0);
            }
            
            logger.debug("Slot {} updated in window {}", slot, windowId);
        }
    }
    
    /**
     * Handle HELD_ITEM_CHANGE (server -> client)
     * Updates selected hotbar slot.
     */
    private void handleHeldItemChange(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerHeldItemChange packet = new WrapperPlayServerHeldItemChange(event);
        
        int slot = packet.getSlot();
        perception.setSelectedSlot(slot);
        
        logger.debug("Held item changed to slot {}", slot);
    }
    
    /**
     * Handle JOIN_GAME
     * Gets the bot's entity ID.
     */
    private void handleJoinGame(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerJoinGame packet = new WrapperPlayServerJoinGame(event);
        
        int entityId = packet.getEntityId();
        perception.setEntityId(entityId);
        entityIdToBotUuid.put(entityId, perception.getBotUuid());
        
        logger.info("Bot {} joined game with entity ID {}", perception.getBotUuid(), entityId);
    }
    
    /**
     * Handle ENTITY_STATUS
     * Detects when bot or entities take damage.
     */
    private void handleEntityStatus(PacketSendEvent event, BotPerception perception) {
        WrapperPlayServerEntityStatus packet = new WrapperPlayServerEntityStatus(event);
        
        int entityId = packet.getEntityId();
        int status = packet.getStatus();
        
        // Status 2 = Entity hurt animation
        if (status == 2) {
            if (entityId == perception.getEntityId()) {
                // Bot was hurt
                perception.setLastHurtTime(System.currentTimeMillis());
                logger.debug("Bot was hurt!");
            } else if (entityId == perception.getTargetEntityId()) {
                // Target was hurt (we hit them)
                perception.setLastAttackTime(System.currentTimeMillis());
                logger.debug("Target {} was hurt!", entityId);
            }
        }
    }
    
    /**
     * Initialize and register this listener with PacketEvents.
     */
    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        logger.info("BotPacketListener registered with PacketEvents");
    }
    
    /**
     * Unregister this listener.
     */
    public void unregister() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        logger.info("BotPacketListener unregistered from PacketEvents");
    }
    
    /**
     * Set debug mode.
     */
    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
    }
}
