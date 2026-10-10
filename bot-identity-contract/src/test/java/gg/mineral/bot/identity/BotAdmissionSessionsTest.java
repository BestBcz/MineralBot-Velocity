package gg.mineral.bot.identity;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BotAdmissionSessionsTest {
    private final long now = 1000000L;
    private final UUID owner = UUID.randomUUID();
    private BotIdentityProtocol.Registration request(String token, UUID uuid, long time) {
        return new BotIdentityProtocol.Registration(UUID.randomUUID(),token,owner,"Micet",uuid,"_"+token,time);
    }
    @Test void loginRequiresTheExactSignedUuidAndName() {
        BotAdmissionSessions sessions=new BotAdmissionSessions();
        UUID expected=BotUuid.create();
        assertTrue(sessions.accept(request("ABC123",expected,now),now));
        assertFalse(sessions.authenticate("ABC123",BotUuid.create(),"_ABC123",now));
        assertFalse(sessions.authenticate("ABC123",expected,"_OTHER1",now));
        assertTrue(sessions.authenticate("ABC123",expected,"_ABC123",now));
        sessions.forget("ABC123");
        assertFalse(sessions.authenticate("ABC123",expected,"_ABC123",now));
    }
    @Test void signedStartupRetryReplacesOnlyAnUnclaimedBindingAndRejectsLateOldUuids() {
        BotAdmissionSessions sessions=new BotAdmissionSessions();
        UUID first=BotUuid.create(),next=BotUuid.create();
        BotIdentityProtocol.Registration old=request("ABC123",first,now);
        assertTrue(sessions.accept(old,now));
        BotIdentityProtocol.Registration retry=request("ABC123",next,now+1);
        assertTrue(sessions.accept(retry,now+1));
        assertFalse(sessions.accept(old,now+1));
        assertFalse(sessions.authenticate("ABC123",first,"_ABC123",now+1));
        assertTrue(sessions.authenticate("ABC123",next,"_ABC123",now+1));
        assertFalse(sessions.accept(request("ABC123",BotUuid.create(),now+2),now+2));
        assertTrue(sessions.accept(retry,now+2));
    }
    @Test void failedLoginReleasesOnlyItsOwnBindingSoStartupCanRetry() {
        BotAdmissionSessions sessions = new BotAdmissionSessions();
        UUID uuid = BotUuid.create();
        assertTrue(sessions.accept(request("ABC123", uuid, now), now));
        assertTrue(sessions.authenticate("ABC123", uuid, "_ABC123", now));
        assertFalse(sessions.release("ABC123", BotUuid.create()));
        assertFalse(sessions.accept(request("ABC123", BotUuid.create(), now + 1), now + 1));
        assertTrue(sessions.release("ABC123", uuid));
        assertTrue(sessions.accept(request("ABC123", BotUuid.create(), now + 1), now + 1));
    }
    @Test void conflictingRegistrationIdOrUuidInAnotherRequestCannotBind() {
        BotAdmissionSessions sessions=new BotAdmissionSessions();
        UUID uuid=BotUuid.create();BotIdentityProtocol.Registration original=request("ABC123",uuid,now);
        assertTrue(sessions.accept(original,now));
        BotIdentityProtocol.Registration conflict=new BotIdentityProtocol.Registration(original.id,"XYZ789",owner,"Micet",uuid,"_XYZ789",now);
        assertFalse(sessions.accept(conflict,now));
        assertFalse(sessions.accept(request("XYZ789",uuid,now),now));
    }
    @Test void unclaimedReservationsExpireButPersistenceStillBlocksTheUuid() {
        BotAdmissionSessions sessions=new BotAdmissionSessions();UUID uuid=BotUuid.create();
        assertTrue(sessions.accept(request("ABC123",uuid,now),now));
        assertFalse(sessions.authenticate("ABC123",uuid,"_ABC123",now+60001));
        BotPersistenceGuard guard=new BotPersistenceGuard();AtomicInteger writes=new AtomicInteger();
        assertNull(guard.persist(uuid,writes::incrementAndGet));assertEquals(0,writes.get());
        assertNull(guard.persist(Arrays.asList(UUID.randomUUID(),uuid),writes::incrementAndGet));
        assertEquals(0,writes.get());
    }
    @Test void delayedWritesAfterApprovedLegacyQuarantineCannotRecreateTheRecord() {
        BotPersistenceGuard guard=new BotPersistenceGuard();UUID old=UUID.randomUUID();AtomicInteger writes=new AtomicInteger();
        guard.coordinate(old,()->{guard.markBot(old);return null;});
        guard.persist(old,writes::incrementAndGet);
        guard.persist(Arrays.asList(UUID.randomUUID(),old),writes::incrementAndGet);
        assertEquals(0,writes.get());
        assertEquals(Integer.valueOf(1),guard.persist(UUID.randomUUID(),writes::incrementAndGet));
    }
}
