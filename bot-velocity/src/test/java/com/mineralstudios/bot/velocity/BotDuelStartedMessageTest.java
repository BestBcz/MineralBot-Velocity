package com.mineralstudios.bot.velocity;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BotDuelStartedMessageTest {
    private final UUID owner = new UUID(0, 1), bot = new UUID(0, 2), target = new UUID(0, 3);
    private final UUID ally = new UUID(0, 4), humanAlly = new UUID(0, 5);

    private ByteArrayOutputStream header() throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        // Matches BotDuelListener.sendBotDuelStarted in Practice.
        out.writeUTF("BotDuelStarted");
        out.writeUTF(owner.toString());
        out.writeUTF(bot.toString());
        out.writeUTF("NODEBUFF");
        out.writeUTF("normal");
        out.writeUTF("request-token");
        return bytes;
    }

    private byte[] party(String targetValue, int count, String... friendlies) throws IOException {
        var bytes = header();
        var out = new DataOutputStream(bytes);
        out.writeUTF(targetValue);
        out.writeInt(count);
        for (String friendly : friendlies) out.writeUTF(friendly);
        return bytes.toByteArray();
    }

    @Test void readsActualPartyWireFormatWithoutAnAdmissionVersion() throws Exception {
        var message = BotDuelStartedMessage.decode(party(target.toString(), 2, ally.toString(), humanAlly.toString()));
        assertEquals(owner, message.ownerUuid());
        assertEquals(bot, message.botUuid());
        assertEquals(target, message.targetUuid());
        assertEquals("request-token", message.requestToken());
        assertEquals(Set.of(ally, humanAlly), message.friendlyUuids());
        assertThrows(UnsupportedOperationException.class, () -> message.friendlyUuids().clear());
    }

    @Test void acceptsAnEntirelyAbsentLegacyExtensionAndAnExplicitEmptyTeam() throws Exception {
        var legacy = BotDuelStartedMessage.decode(header().toByteArray());
        assertEquals(owner, legacy.targetUuid());
        assertTrue(legacy.friendlyUuids().isEmpty());
        var solo = BotDuelStartedMessage.decode(party(target.toString(), 0));
        assertEquals(target, solo.targetUuid());
        assertTrue(solo.friendlyUuids().isEmpty());
    }

    @Test void rejectsEveryPartiallyTruncatedPartyExtension() throws Exception {
        byte[] complete = party(target.toString(), 2, ally.toString(), humanAlly.toString());
        int legacyEnd = header().size();
        for (int end = 0; end < complete.length; end++) {
            if (end == legacyEnd) continue;
            byte[] truncated = Arrays.copyOf(complete, end);
            assertThrows(IOException.class, () -> BotDuelStartedMessage.decode(truncated), "length=" + end);
        }
    }

    @Test void rejectsInvalidUuidsCountsAndTrailingData() throws Exception {
        assertThrows(IOException.class, () -> BotDuelStartedMessage.decode(party("invalid-target", 0)));
        assertThrows(IOException.class, () -> BotDuelStartedMessage.decode(party(target.toString(), 1,
                "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz")));
        assertThrows(IOException.class, () -> BotDuelStartedMessage.decode(party(target.toString(), -1)));
        assertThrows(IOException.class, () -> BotDuelStartedMessage.decode(party(target.toString(), Integer.MAX_VALUE)));
        byte[] valid = party(target.toString(), 0);
        assertThrows(IOException.class, () -> BotDuelStartedMessage.decode(Arrays.copyOf(valid, valid.length + 1)));
    }

    @Test void rejectsTheErroneousVersionPrefixedExtension() throws Exception {
        var bytes = header();
        var out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeUTF(target.toString());
        out.writeInt(0);
        assertThrows(IOException.class, () -> BotDuelStartedMessage.decode(bytes.toByteArray()));
    }
}
