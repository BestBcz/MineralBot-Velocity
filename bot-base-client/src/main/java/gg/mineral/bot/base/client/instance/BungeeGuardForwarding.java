package gg.mineral.bot.base.client.instance;

import com.google.common.net.InetAddresses;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** In-memory credentials; never put this object or the generated address in diagnostics. */
public final class BungeeGuardForwarding {
    private final String secret;
    private final String forwardedIp;

    private BungeeGuardForwarding(String secret, String forwardedIp) {
        this.secret = secret;
        this.forwardedIp = forwardedIp;
    }

    public static BungeeGuardForwarding load(Path file, String forwardedIp) throws IOException {
        if (!InetAddresses.isInetAddress(forwardedIp)) {
            throw new IllegalArgumentException("FORWARDED_IP_INVALID");
        }
        String secret;
        try {
            secret = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("SECRET_FILE_UNREADABLE");
        }
        if (secret.endsWith("\r\n")) secret = secret.substring(0, secret.length() - 2);
        else if (secret.endsWith("\n")) secret = secret.substring(0, secret.length() - 1);
        if (secret.isEmpty()) throw new IllegalArgumentException("SECRET_EMPTY");
        if (secret.indexOf('\0') >= 0 || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0
                || secret.getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new IllegalArgumentException("SECRET_INVALID");
        }
        return new BungeeGuardForwarding(secret, forwardedIp);
    }

    public String getForwardedIp() { return forwardedIp; }

    /** Wire semantics verified against Velocity PlayerDataForwarding (3.5 development branch). */
    public String createAddress(String host, GameProfile profile) {
        if (host == null || host.isBlank() || host.length() > 255 || host.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("TARGET_ADDRESS_INVALID");
        }
        JsonArray properties = new JsonArray();
        for (Property property : profile.getProperties().values()) {
            if ("bungeeguard-token".equals(property.getName())) {
                throw new IllegalArgumentException("DUPLICATE_BUNGEEGUARD_PROPERTY");
            }
            JsonObject json = new JsonObject();
            json.addProperty("name", property.getName());
            json.addProperty("value", property.getValue());
            if (property.getSignature() != null) json.addProperty("signature", property.getSignature());
            properties.add(json);
        }
        JsonObject token = new JsonObject();
        token.addProperty("name", "bungeeguard-token");
        token.addProperty("value", secret);
        token.addProperty("signature", "");
        properties.add(token);
        String address = host + '\0' + forwardedIp + '\0'
                + profile.getId().toString().replace("-", "") + '\0' + new Gson().toJson(properties);
        // The 1.7.10 outbound PacketBuffer enforces a UTF-8 byte limit, not the vanilla
        // inbound hostname limit of 255. Bungee-enabled backends accept extended hosts.
        if (address.length() > 2500 || address.getBytes(StandardCharsets.UTF_8).length > 32767) {
            throw new IllegalArgumentException("FORWARDING_TOO_LONG");
        }
        return address;
    }

    public String redact(String text) {
        if (text == null) return null;
        String escaped = new Gson().toJson(secret);
        return text.replace(secret, "[redacted]")
                .replace(escaped.substring(1, escaped.length() - 1), "[redacted]");
    }

    @Override public String toString() { return "BungeeGuardForwarding{credentials=redacted}"; }
}
