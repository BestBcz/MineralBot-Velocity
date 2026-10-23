package com.mineralstudios.bot.velocity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FriendlyUuidMappingsTest {
    private final UUID self = new UUID(0, 1), backend = new UUID(0, 2), internal = new UUID(0, 3);
    private final UUID configured = new UUID(0, 4), human = new UUID(0, 5), enemyBot = new UUID(0, 6);
    private final UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:AllyBot".getBytes(StandardCharsets.UTF_8));
    private final Map<UUID, FriendlyUuidMappings.Identity> identities = Map.of(
            self, new FriendlyUuidMappings.Identity(self, "SelfBot"),
            internal, new FriendlyUuidMappings.Identity(configured, "AllyBot"),
            enemyBot, new FriendlyUuidMappings.Identity(enemyBot, "EnemyBot"));

    @Test void lateAcknowledgementCompletesTheEarlierTeammatesAliases() {
        Set<UUID> first = expand(Set.of(backend, human), Set.of(), self, Map.of(), identities);
        assertEquals(Set.of(backend, human), first);
        Set<UUID> next = expand(Set.of(backend, human), first, self, Map.of(backend, internal), identities);
        assertEquals(Set.of(backend, internal, configured, offline, human), next);
        assertEquals(Set.of(backend, human), first, "Publishing a refresh must not clear the previous snapshot");
        assertThrows(UnsupportedOperationException.class, next::clear);
        assertFalse(next.contains(enemyBot));
    }

    @Test void resolvesInternalConfiguredAndOfflineDeclarationsInBothDirections() {
        Set<UUID> expected = Set.of(backend, internal, configured, offline);
        for (UUID declared : expected) {
            assertEquals(expected, expand(Set.of(declared), Set.of(), self,
                    Map.of(backend, internal), identities));
        }
    }

    @Test void disconnectDoesNotTurnALingeringTeammateEntityIntoAnEnemy() {
        Set<UUID> known = expand(Set.of(backend), Set.of(), self, Map.of(backend, internal), identities);
        assertEquals(known, expand(Set.of(backend), known, self, Map.of(), Map.of()));
    }

    @Test void excludesSelfAndDoesNotTreatEveryBotAsFriendly() {
        assertTrue(expand(Set.of(self), Set.of(), self, Map.of(), identities).isEmpty());
        assertTrue(expand(Set.of(), Set.of(), self, Map.of(backend, internal), identities).isEmpty());
    }

    private Set<UUID> expand(Set<UUID> declared, Set<UUID> previous, UUID self,
                             Map<UUID, UUID> mappings, Map<UUID, FriendlyUuidMappings.Identity> bots) {
        return FriendlyUuidMappings.expand(declared, previous, self, FriendlyUuidMappings.index(mappings, bots));
    }
}
