package com.mineralstudios.bot.velocity;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class FriendlyUuidMappings {
    record Identity(UUID configuredUuid, String username) { }

    private FriendlyUuidMappings() { }

    /** Build once per refresh, rather than scanning every active bot for each ally. */
    static Map<UUID, Set<UUID>> index(Map<UUID, UUID> serverToInternal, Map<UUID, Identity> identities) {
        Map<UUID, Set<UUID>> groups = new HashMap<>();
        identities.forEach((internal, identity) -> {
            Set<UUID> aliases = groups.computeIfAbsent(internal, ignored -> new HashSet<>());
            aliases.add(internal);
            aliases.add(identity.configuredUuid());
            if (identity.username() != null && !identity.username().isEmpty()) {
                aliases.add(UUID.nameUUIDFromBytes(("OfflinePlayer:" + identity.username()).getBytes(StandardCharsets.UTF_8)));
            }
        });
        serverToInternal.forEach((backend, internal) -> {
            Set<UUID> aliases = groups.computeIfAbsent(internal, ignored -> new HashSet<>());
            aliases.add(internal);
            aliases.add(backend);
        });
        Map<UUID, Set<UUID>> index = new HashMap<>();
        groups.values().forEach(aliases -> {
            Set<UUID> snapshot = Set.copyOf(aliases);
            aliases.forEach(alias -> index.put(alias, snapshot));
        });
        return index;
    }

    /** Expands declared allies only; another bot is not implicitly a teammate. */
    static Set<UUID> expand(Set<UUID> declared, Set<UUID> previous, UUID self, Map<UUID, Set<UUID>> index) {
        Set<UUID> resolved = new HashSet<>(previous);
        resolved.addAll(declared);
        for (UUID uuid : new HashSet<>(resolved)) {
            resolved.addAll(index.getOrDefault(uuid, Set.of()));
        }
        resolved.removeAll(index.getOrDefault(self, Set.of(self)));
        // Publish a complete, immutable snapshot. Previously learned aliases survive
        // a teammate's disconnect until this bot's own match is cleaned up.
        return Collections.unmodifiableSet(resolved);
    }

}
