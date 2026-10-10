package gg.mineral.bot.identity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Java 8 wire codec. Keep the identical source in the backend and proxy distributions. */
public final class BotIdentityProtocol {
    public static final int VERSION = 2;
    public static final String REGISTER = "BotIdentityRegister";
    public static final String READY = "BotIdentityReady";
    public static final String REJECTED = "BotIdentityRejected";
    public static final long MAX_AGE_MILLIS = 45000L;
    private static final int MAC_SIZE = 32;
    private static final byte[] DOMAIN = "MineralBotIdentity/v2\0".getBytes(StandardCharsets.UTF_8);
    private BotIdentityProtocol() { }

    public static byte[] sign(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(DOMAIN);
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Identity signing failed", e); }
    }

    public static final class Registration {
        public final UUID id, ownerUuid, botUuid;
        public final String token, server, name;
        public final long timestamp;
        public Registration(UUID id, String token, UUID ownerUuid, String server, UUID botUuid, String name, long timestamp) {
            this.id=id; this.token=token; this.ownerUuid=ownerUuid; this.server=server;
            this.botUuid=botUuid; this.name=name; this.timestamp=timestamp;
        }
        public void validate(long now) throws IOException {
            if (id.version()!=4 || !BotUuid.isBot(botUuid) || ownerUuid.equals(botUuid)
                    || !token.matches("[A-Z0-9]{6}") || !("_"+token).equals(name)
                    || server.isEmpty() || server.length()>64 || server.indexOf('\0')>=0
                    || timestamp < now-MAX_AGE_MILLIS || timestamp > now+5000L)
                throw new IOException("Invalid or expired bot identity registration");
        }
        public boolean sameIdentity(Registration other) {
            return id.equals(other.id) && token.equals(other.token) && ownerUuid.equals(other.ownerUuid)
                    && server.equals(other.server) && botUuid.equals(other.botUuid)
                    && name.equals(other.name) && timestamp==other.timestamp;
        }
    }
    public static final class Reply {
        public final UUID id, botUuid;
        public final String token, server, reason;
        public final boolean accepted;
        public Reply(UUID id, UUID botUuid, String token, String server, boolean accepted, String reason) {
            this.id=id; this.botUuid=botUuid; this.token=token; this.server=server; this.accepted=accepted; this.reason=reason;
        }
    }

    public static byte[] registration(Registration r, Function<byte[], byte[]> signer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(REGISTER); out.writeInt(VERSION); out.writeUTF(r.id.toString()); out.writeUTF(r.token);
        out.writeUTF(r.ownerUuid.toString()); out.writeUTF(r.server); out.writeUTF(r.botUuid.toString());
        out.writeUTF(r.name); out.writeLong(r.timestamp);
        return signed(bytes, signer);
    }
    public static Registration readRegistration(byte[] raw, Function<byte[], byte[]> signer) throws IOException {
        DataInputStream in = verified(raw, signer);
        if (!REGISTER.equals(in.readUTF()) || in.readInt()!=VERSION) throw new IOException("Identity protocol version mismatch");
        try {
            Registration r = new Registration(UUID.fromString(in.readUTF()), in.readUTF(), UUID.fromString(in.readUTF()),
                    in.readUTF(), UUID.fromString(in.readUTF()), in.readUTF(), in.readLong());
            if (in.available()!=0) throw new IOException("Trailing identity fields");
            return r;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid identity UUID", e); }
    }
    public static byte[] reply(Registration r, boolean accepted, String reason, Function<byte[], byte[]> signer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(accepted ? READY : REJECTED); out.writeInt(VERSION);
        out.writeUTF(r.id.toString()); out.writeUTF(r.botUuid.toString()); out.writeUTF(r.token);
        out.writeUTF(r.server); out.writeUTF(reason);
        return signed(bytes, signer);
    }
    public static Reply readReply(byte[] raw, Function<byte[], byte[]> signer) throws IOException {
        DataInputStream in = verified(raw, signer);
        String type = in.readUTF();
        if ((!READY.equals(type) && !REJECTED.equals(type)) || in.readInt()!=VERSION) throw new IOException("Invalid identity reply version");
        try {
            Reply reply = new Reply(UUID.fromString(in.readUTF()), UUID.fromString(in.readUTF()), in.readUTF(),
                    in.readUTF(), READY.equals(type), in.readUTF());
            if (in.available()!=0) throw new IOException("Trailing identity reply fields");
            return reply;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid reply UUID", e); }
    }
    private static byte[] signed(ByteArrayOutputStream bytes, Function<byte[], byte[]> signer) throws IOException {
        byte[] body = bytes.toByteArray();
        byte[] signature = signer.apply(body);
        if (signature.length!=MAC_SIZE) throw new IOException("Invalid signature size");
        bytes.write(signature);
        return bytes.toByteArray();
    }
    private static DataInputStream verified(byte[] raw, Function<byte[], byte[]> signer) throws IOException {
        if (raw.length<=MAC_SIZE || raw.length>2048) throw new IOException("Invalid identity message size");
        byte[] body = java.util.Arrays.copyOf(raw, raw.length-MAC_SIZE);
        byte[] signature = java.util.Arrays.copyOfRange(raw, body.length, raw.length);
        if (!MessageDigest.isEqual(signature, signer.apply(body))) throw new IOException("Invalid identity signature");
        return new DataInputStream(new ByteArrayInputStream(body));
    }
}
