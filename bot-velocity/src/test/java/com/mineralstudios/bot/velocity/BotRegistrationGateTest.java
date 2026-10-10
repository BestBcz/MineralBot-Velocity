package com.mineralstudios.bot.velocity;

import gg.mineral.bot.identity.BotIdentityProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class BotRegistrationGateTest {
    private final String secret="identity-test-secret";
    private final AtomicLong clock=new AtomicLong(1000000L);
    private final Function<byte[],byte[]> signer=raw -> BotIdentityProtocol.sign(secret,raw);
    private BotIdentityProtocol.Registration registration() {
        return new BotIdentityProtocol.Registration(UUID.randomUUID(),"ABC123",UUID.randomUUID(),"Micet",gg.mineral.bot.identity.BotUuid.create(),"_ABC123",clock.get());
    }
    @Test void doesNotConnectBeforeAuthenticatedMatchingAcknowledgement() throws Exception {
        BotRegistrationGate gate=new BotRegistrationGate(signer,clock::get);
        BotIdentityProtocol.Registration r=registration();
        AtomicInteger sent=new AtomicInteger(),connected=new AtomicInteger();
        var result=gate.register(r,bytes -> sent.incrementAndGet());
        result.thenAccept(ok -> { if(ok) connected.incrementAndGet(); });
        assertEquals(1,sent.get()); assertEquals(0,connected.get());
        byte[] reply=BotIdentityProtocol.reply(r,true,"READY",signer);
        assertFalse(gate.accept(reply,"lobby")); assertFalse(result.isDone());
        assertTrue(gate.accept(reply,"Micet")); assertTrue(result.join()); assertEquals(1,connected.get());
        assertFalse(gate.accept(reply,"Micet")); assertEquals(1,connected.get());
    }
    @Test void retriesExactlyThreeTimesAndNeverConnectsOnLostReplies() {
        BotRegistrationGate gate=new BotRegistrationGate(signer,clock::get);
        AtomicInteger sent=new AtomicInteger();
        var result=gate.register(registration(),bytes -> sent.incrementAndGet());
        clock.addAndGet(5000); gate.tick(); clock.addAndGet(5000); gate.tick();
        assertEquals(3,sent.get()); assertFalse(result.isDone());
        clock.addAndGet(5000); gate.tick(); assertFalse(result.join());
        clock.addAndGet(5000); gate.tick(); assertEquals(3,sent.get());
    }
    @Test void storageFailureCanRecoverOnRetryButInvalidRequestCannot() throws Exception {
        BotRegistrationGate gate=new BotRegistrationGate(signer,clock::get);
        BotIdentityProtocol.Registration r=registration();
        var result=gate.register(r,bytes -> {});
        gate.accept(BotIdentityProtocol.reply(r,false,"STORAGE_UNAVAILABLE",signer),"Micet");
        assertFalse(result.isDone()); clock.addAndGet(5000); gate.tick();
        gate.accept(BotIdentityProtocol.reply(r,true,"READY",signer),"Micet"); assertTrue(result.join());
        r=registration(); var rejected=gate.register(r,bytes -> {});
        gate.accept(BotIdentityProtocol.reply(r,false,"INVALID_PENDING_REQUEST",signer),"Micet");
        assertFalse(rejected.join());
    }
    @Test void forgedRepliesAndConflictingUuidsCannotAuthorizeConnection() throws Exception {
        BotRegistrationGate gate=new BotRegistrationGate(signer,clock::get);
        BotIdentityProtocol.Registration r=registration();
        var result=gate.register(r,bytes -> {});
        byte[] forged=BotIdentityProtocol.reply(r,true,"READY",bytes -> BotIdentityProtocol.sign("wrong",bytes));
        assertThrows(java.io.IOException.class,() -> gate.accept(forged,"Micet"));
        BotIdentityProtocol.Registration conflict=new BotIdentityProtocol.Registration(r.id,r.token,r.ownerUuid,r.server,UUID.randomUUID(),r.name,r.timestamp);
        assertFalse(gate.accept(BotIdentityProtocol.reply(conflict,true,"READY",signer),"Micet"));
        assertFalse(result.isDone());
    }
    @Test void cancellationAndShutdownCancelPendingRegistrations() {
        BotRegistrationGate gate=new BotRegistrationGate(signer,clock::get);
        var first=gate.register(registration(),bytes -> {});
        gate.cancelToken("ABC123"); assertFalse(first.join());
        var second=gate.register(registration(),bytes -> {}); gate.close(); assertFalse(second.join());
        assertFalse(gate.register(registration(),bytes -> {}).join());
    }
    @Test void forwardingCredentialSignerMatchesBackendCodec(@TempDir Path dir) throws Exception {
        Path key=dir.resolve("forwarding.secret"); Files.writeString(key,secret+"\n");
        var forwarding=gg.mineral.bot.base.client.instance.BungeeGuardForwarding.load(key,"127.0.0.1");
        var r=registration();
        byte[] raw=BotIdentityProtocol.registration(r,forwarding::signIdentityMessage);
        assertTrue(r.sameIdentity(BotIdentityProtocol.readRegistration(raw,signer)));
    }
}
