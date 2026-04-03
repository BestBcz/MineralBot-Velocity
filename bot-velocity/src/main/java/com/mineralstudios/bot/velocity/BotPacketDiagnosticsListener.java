package com.mineralstudios.bot.velocity;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.velocitypowered.api.proxy.Player;
import java.util.Locale;

final class BotPacketDiagnosticsListener extends PacketListenerAbstract {

    private final VelocityBotManager manager;

    BotPacketDiagnosticsListener(VelocityBotManager manager) {
        super(PacketListenerPriority.MONITOR);
        this.manager = manager;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        String packetKey = normalize(event.getPacketType());
        if (packetKey == null) {
            return;
        }

        Object playerObject = event.getPlayer();
        if (!(playerObject instanceof Player player)) {
            return;
        }

        manager.recordProxyPacket(player.getUsername(), packetKey);
    }

    private String normalize(PacketTypeCommon packetType) {
        if (packetType == null) {
            return null;
        }

        String name = packetType.getName();
        if (name == null || name.isEmpty()) {
            return null;
        }

        String normalized = name.toUpperCase(Locale.ROOT);

        if ("PLAYER_POSITION_AND_LOOK".equals(normalized)) {
            return BotSessionDiagnostics.PLAYER_POS_LOOK;
        }
        if ("SPAWN_PLAYER".equals(normalized)) {
            return BotSessionDiagnostics.SPAWN_PLAYER;
        }
        if ("SET_SLOT".equals(normalized)) {
            return BotSessionDiagnostics.SET_SLOT;
        }
        if ("WINDOW_ITEMS".equals(normalized)) {
            return BotSessionDiagnostics.WINDOW_ITEMS;
        }
        if ("ENTITY_METADATA".equals(normalized)) {
            return BotSessionDiagnostics.ENTITY_METADATA;
        }
        if ("ENTITY_VELOCITY".equals(normalized)) {
            return BotSessionDiagnostics.ENTITY_VELOCITY;
        }
        if ("DESTROY_ENTITIES".equals(normalized)) {
            return BotSessionDiagnostics.DESTROY_ENTITIES;
        }
        if ("UPDATE_HEALTH".equals(normalized)) {
            return BotSessionDiagnostics.UPDATE_HEALTH;
        }

        if (normalized.contains("ENTITY")
                && (normalized.contains("MOVE")
                        || normalized.contains("POSITION")
                        || normalized.contains("ROTATION")
                        || normalized.contains("LOOK")
                        || normalized.contains("TELEPORT"))) {
            return BotSessionDiagnostics.ENTITY_MOVEMENT;
        }

        return null;
    }
}
