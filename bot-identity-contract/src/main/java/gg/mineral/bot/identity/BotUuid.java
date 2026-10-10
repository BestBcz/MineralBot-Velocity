package gg.mineral.bot.identity;

import java.util.UUID;

/** Reserved custom UUIDs are recognizable on every server without storing an identity. */
public final class BotUuid {
    private static final long PREFIX = 0x4d494e424f548000L; // MINBOT + custom version 8
    private static final long MASK = 0xfffffffffffff000L;
    private BotUuid() { }

    public static UUID create() {
        UUID random = UUID.randomUUID();
        return new UUID(PREFIX | (random.getMostSignificantBits() & 0xfffL),
                (random.getLeastSignificantBits() & 0x3fffffffffffffffL) | 0x8000000000000000L);
    }

    /** Recognition is a persistence rule; HMAC and pending-request validation still authorize login. */
    public static boolean isBot(UUID uuid) {
        return uuid != null && (uuid.getMostSignificantBits() & MASK) == PREFIX && uuid.variant() == 2;
    }
}
