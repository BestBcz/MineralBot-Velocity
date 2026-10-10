package gg.mineral.bot.identity;

import java.util.*;

/** Short-lived signed request bindings. These never determine persistence eligibility. */
public final class BotAdmissionSessions {
    private final Map<String, Binding> tokens = new HashMap<>();
    private final Map<UUID, BotIdentityProtocol.Registration> attempts = new HashMap<>();
    public synchronized boolean accept(BotIdentityProtocol.Registration r, long now) {
        expire(now);
        BotIdentityProtocol.Registration seen = attempts.get(r.id);
        if (seen != null && !seen.sameIdentity(r)) return false;
        Binding old = tokens.get(r.token);
        if (old != null && !old.registration.sameIdentity(r)) {
            if (old.claimed || !old.registration.ownerUuid.equals(r.ownerUuid)
                    || !old.registration.server.equals(r.server) || r.timestamp < old.registration.timestamp) return false;
        }
        for (Binding binding : tokens.values())
            if (binding.registration.botUuid.equals(r.botUuid) && !binding.registration.token.equals(r.token)) return false;
        if (old == null || !old.registration.sameIdentity(r)) tokens.put(r.token, new Binding(r));
        attempts.put(r.id, r);
        return true;
    }
    public synchronized boolean authenticate(String token, UUID uuid, String name, long now) {
        expire(now);
        Binding binding = tokens.get(token);
        if (binding == null || !binding.registration.botUuid.equals(uuid) || !binding.registration.name.equals(name)) return false;
        binding.claimed = true;
        return true;
    }
    public synchronized boolean release(String token, UUID uuid) {
        Binding binding = tokens.get(token);
        if (binding == null || !binding.registration.botUuid.equals(uuid)) return false;
        forget(token);
        return true;
    }
    public synchronized void forget(String token) {
        tokens.remove(token);
        attempts.values().removeIf(r -> r.token.equals(token));
    }
    public synchronized void expire(long now) {
        tokens.values().removeIf(b -> !b.claimed && now - b.registration.timestamp > 60000L);
        attempts.values().removeIf(r -> now - r.timestamp > 60000L);
    }
    public synchronized void clear() { tokens.clear(); attempts.clear(); }
    private static final class Binding {
        final BotIdentityProtocol.Registration registration;
        boolean claimed;
        Binding(BotIdentityProtocol.Registration registration) { this.registration = registration; }
    }
}
