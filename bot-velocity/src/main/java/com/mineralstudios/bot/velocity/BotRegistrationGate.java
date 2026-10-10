package com.mineralstudios.bot.velocity;

import gg.mineral.bot.identity.BotIdentityProtocol;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** A matching signed admission acknowledgement authorizes connection; no storage acknowledgement is required. */
final class BotRegistrationGate implements AutoCloseable {
    private final Function<byte[], byte[]> signer;
    private final LongSupplier clock;
    private final Map<UUID, Waiting> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;
    BotRegistrationGate(Function<byte[], byte[]> signer, LongSupplier clock) { this.signer=signer; this.clock=clock; }

    CompletableFuture<Boolean> register(BotIdentityProtocol.Registration registration, Consumer<byte[]> sender) {
        if (closed) return CompletableFuture.completedFuture(false);
        try {
            registration.validate(clock.getAsLong());
            Waiting state = new Waiting(registration, BotIdentityProtocol.registration(registration, signer), sender);
            if (pending.putIfAbsent(registration.id, state)!=null) throw new IllegalArgumentException("Duplicate registration ID");
            synchronized (state) {
                if (closed) finish(state, false);
                else send(state, clock.getAsLong());
            }
            return state.result;
        } catch (IOException e) { return CompletableFuture.completedFuture(false); }
    }

    void tick() {
        long now = clock.getAsLong();
        for (Waiting state : pending.values()) synchronized (state) {
            if (state.result.isDone() || now < state.nextAttempt) continue;
            if (state.attempts>=3) finish(state, false);
            else send(state, now);
        }
    }

    private void send(Waiting state, long now) {
        state.attempts++;
        state.nextAttempt=now+5000L;
        try { state.sender.accept(state.message); }
        catch (RuntimeException ignored) { /* A transient transport failure consumes this attempt, not the whole request. */ }
    }

    boolean accept(byte[] message, String sourceServer) throws IOException {
        BotIdentityProtocol.Reply reply = BotIdentityProtocol.readReply(message, signer);
        Waiting state = pending.get(reply.id);
        if (state==null) return false;
        synchronized (state) {
            if (!state.registration.botUuid.equals(reply.botUuid) || !state.registration.token.equals(reply.token)
                    || !state.registration.server.equals(reply.server) || !reply.server.equals(sourceServer)) return false;
            if (!reply.accepted && "STORAGE_UNAVAILABLE".equals(reply.reason)) return true;
            finish(state, reply.accepted);
            return true;
        }
    }
    void cancelToken(String token) {
        for (Waiting state : pending.values()) if (state.registration.token.equals(token)) {
            synchronized (state) { finish(state, false); }
        }
    }
    private void finish(Waiting state, boolean accepted) {
        pending.remove(state.registration.id, state);
        state.result.complete(accepted);
    }
    public void close() {
        closed=true;
        for (Waiting state : pending.values()) synchronized (state) { finish(state, false); }
    }
    private static final class Waiting {
        final BotIdentityProtocol.Registration registration;
        final byte[] message;
        final Consumer<byte[]> sender;
        final CompletableFuture<Boolean> result=new CompletableFuture<>();
        int attempts;
        long nextAttempt;
        Waiting(BotIdentityProtocol.Registration r, byte[] message, Consumer<byte[]> sender) {
            this.registration=r; this.message=message; this.sender=sender;
        }
    }
}
