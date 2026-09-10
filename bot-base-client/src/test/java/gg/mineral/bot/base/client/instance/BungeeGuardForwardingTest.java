package gg.mineral.bot.base.client.instance;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.handshake.client.C00Handshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BungeeGuardForwardingTest {
    @TempDir Path directory;
    private final UUID uuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    private BungeeGuardForwarding forwarding(String secret) throws Exception {
        Path file = directory.resolve("test.secret");
        Files.writeString(file, secret);
        return BungeeGuardForwarding.load(file, "127.0.0.1");
    }

    @Test void matchesVelocityWireFormatAndRetainsProfileProperties() throws Exception {
        var profile = new GameProfile(uuid, "TestBot");
        profile.getProperties().put("textures", new Property("textures", "skin", "signed"));
        String address = forwarding("test-token\r\n").createAddress("practice", profile);
        assertEquals("practice\0" + "127.0.0.1\0" + "0123456789abcdef0123456789abcdef\0"
                + "[{\"name\":\"textures\",\"value\":\"skin\",\"signature\":\"signed\"},"
                + "{\"name\":\"bungeeguard-token\",\"value\":\"test-token\",\"signature\":\"\"}]", address);
        assertEquals(1, profile.getProperties().size(), "Do not inject credentials into the session profile");
    }

    @Test void encodesExtendedHostWithProtocolFiveAndBackendPort() throws Exception {
        String address = forwarding("x".repeat(300)).createAddress("practice", new GameProfile(uuid, "TestBot"));
        var buffer = new PacketBuffer(Unpooled.buffer());
        try {
            new C00Handshake(5, address, 25571, EnumConnectionState.LOGIN).writePacketData(buffer);
            assertEquals(5, buffer.readVarIntFromBuffer());
            assertEquals(address.getBytes(StandardCharsets.UTF_8).length, buffer.readVarIntFromBuffer());
            byte[] bytes = new byte[address.getBytes(StandardCharsets.UTF_8).length];
            buffer.readBytes(bytes);
            assertEquals(address, new String(bytes, StandardCharsets.UTF_8));
            assertEquals(25571, buffer.readUnsignedShort());
            assertEquals(2, buffer.readVarIntFromBuffer());
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void preservesWhitespaceAndJsonEscapesWithoutLeakingCredentials() throws Exception {
        String secret = " secret\"\\<> ";
        var forwarding = forwarding(secret + "\n");
        String address = forwarding.createAddress("practice", new GameProfile(uuid, "TestBot"));
        String json = address.substring(address.lastIndexOf('\0') + 1);
        var property = JsonParser.parseString(json).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(secret, property.get("value").getAsString());
        assertEquals("", property.get("signature").getAsString());
        assertFalse(forwarding.toString().contains(secret));
        assertFalse(forwarding.redact(address).contains(secret));
        assertFalse(forwarding.redact(address).contains("secret"));
    }

    @Test void rejectsMissingEmptyMalformedAndOversizedCredentials() throws Exception {
        assertThrows(java.io.IOException.class, () -> BungeeGuardForwarding.load(directory.resolve("missing"), "127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> forwarding("\n"));
        assertThrows(IllegalArgumentException.class, () -> forwarding("token\nextra"));
        assertThrows(IllegalArgumentException.class, () -> forwarding("token\0extra"));
        var large = forwarding("x".repeat(2500));
        assertThrows(IllegalArgumentException.class, () -> large.createAddress("practice", new GameProfile(uuid, "TestBot")));
    }

    @Test void rejectsHostInjectionAndDuplicateTokens() throws Exception {
        var forwarding = forwarding("test-token");
        var profile = new GameProfile(uuid, "TestBot");
        assertThrows(IllegalArgumentException.class, () -> forwarding.createAddress("practice\0injected", profile));
        profile.getProperties().put("bungeeguard-token", new Property("bungeeguard-token", "another"));
        assertThrows(IllegalArgumentException.class, () -> forwarding.createAddress("practice", profile));
        assertThrows(IllegalArgumentException.class, () -> BungeeGuardForwarding.load(directory.resolve("test.secret"), "player.example.org"));
    }
}
