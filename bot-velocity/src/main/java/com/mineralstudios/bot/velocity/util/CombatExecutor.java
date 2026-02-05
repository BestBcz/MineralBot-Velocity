package com.mineralstudios.bot.velocity.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Utility class for safely execution bot actions via PacketEvents.
 * Handles Velocity-specific channel checks and 1.7.10 compatibility.
 */
public class CombatExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CombatExecutor.class);

    /**
     * Sends a packet to the server on behalf of the bot.
     * 
     * @param botUuid The UUID of the bot.
     * @param packet  The packet wrapper to send.
     */
    public static void sendBotAction(UUID botUuid, PacketWrapper<?> packet) {
        if (botUuid == null || packet == null)
            return;

        try {
            User user = PacketEvents.getAPI().getProtocolManager().getUser(botUuid);

            // Check if user exists and connection is open
            if (user != null && user.isOpen()) {
                user.sendPacket(packet);
            } else {
                // Determine why it failed for debugging
                if (user == null) {
                    // This is expected if the bot disconnected or hasn't fully joined
                    // logger.debug("Cannot send packet - User not found for bot {}", botUuid);
                } else {
                    // logger.debug("Cannot send packet - Connection closed for bot {}", botUuid);
                }
            }
        } catch (Exception e) {
            logger.error("Error sending packet for bot {}: {}", botUuid, e.getMessage());
        }
    }
}
