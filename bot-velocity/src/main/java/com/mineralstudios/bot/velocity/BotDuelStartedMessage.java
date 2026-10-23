package com.mineralstudios.bot.velocity;

import gg.mineral.bot.api.configuration.BotDifficulty;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The Practice match-start format, separate from the versioned admission protocol. */
record BotDuelStartedMessage(UUID ownerUuid, UUID botUuid, String kitType, BotDifficulty difficulty,
                             String requestToken, UUID targetUuid, Set<UUID> friendlyUuids) {
    static BotDuelStartedMessage decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!"BotDuelStarted".equals(in.readUTF())) {
                throw new IOException("Unexpected match-start subchannel");
            }
            UUID owner = UUID.fromString(in.readUTF());
            UUID bot = UUID.fromString(in.readUTF());
            String kit = in.readUTF();
            BotDifficulty difficulty = BotDifficulty.fromId(in.readUTF());
            String token = in.readUTF();

            // Only an entirely absent extension denotes the old 1v1 format.
            if (in.available() == 0) {
                return new BotDuelStartedMessage(owner, bot, kit, difficulty, token, owner, Set.of());
            }
            UUID target = UUID.fromString(in.readUTF());
            int count = in.readInt();
            if (count < 0 || count > in.available() / 38) {
                throw new IOException("Invalid match-start friendly count");
            }
            Set<UUID> friendlies = new HashSet<>();
            for (int index = 0; index < count; index++) {
                friendlies.add(UUID.fromString(in.readUTF()));
            }
            if (in.available() != 0) {
                throw new IOException("Unexpected match-start trailing data");
            }
            return new BotDuelStartedMessage(owner, bot, kit, difficulty, token, target, Set.copyOf(friendlies));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid match-start value", e);
        }
    }
}
