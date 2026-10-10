package gg.mineral.bot.identity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** UUID classification never depends on authentication, a plugin API or a database. */
public final class BotPersistenceGuard {
    private final Object[] locks = new Object[256];
    private final Set<UUID> denied = ConcurrentHashMap.newKeySet();
    private final Predicate<UUID> lookup;
    public BotPersistenceGuard() { this(BotUuid::isBot); }
    public BotPersistenceGuard(Predicate<UUID> lookup) {
        this.lookup = Objects.requireNonNull(lookup);
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }
    private int stripe(UUID uuid) { return (Objects.requireNonNull(uuid).hashCode() & Integer.MAX_VALUE) % locks.length; }
    public Object lock(UUID uuid) { return locks[stripe(uuid)]; }
    public void markBot(UUID uuid) { if (uuid != null && !lookup.test(uuid)) denied.add(uuid); }
    public boolean check(UUID uuid) { return uuid != null && (denied.contains(uuid) || lookup.test(uuid)); }
    public boolean isKnown(UUID uuid) { return check(uuid); }
    public <T> T coordinate(UUID uuid, Supplier<T> action) {
        synchronized (lock(uuid)) { return action.get(); }
    }
    public <T> T persist(UUID uuid, Supplier<T> action) {
        if (check(uuid)) return null;
        return coordinate(uuid, () -> check(uuid) ? null : action.get());
    }
    public <T> T persist(Collection<UUID> uuids, Supplier<T> action) {
        if (uuids.stream().anyMatch(this::check)) return null;
        int[] stripes = uuids.stream().mapToInt(this::stripe).distinct().sorted().toArray();
        return locked(stripes, 0, () -> uuids.stream().anyMatch(this::check) ? null : action.get());
    }
    private <T> T locked(int[] stripes, int index, Supplier<T> action) {
        if (index == stripes.length) return action.get();
        synchronized (locks[stripes[index]]) { return locked(stripes, index + 1, action); }
    }
}
