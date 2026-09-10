package com.mineralstudios.bot.velocity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectConnectionDiagnosticsTest {
    @Test void controlRepliesUseCapturedBackendSender() {
        var diagnostics = new BotSessionDiagnostics(UUID.randomUUID(), UUID.randomUUID(), "TestBot", "test", 0);
        var received = new AtomicReference<byte[]>();
        diagnostics.setControlSender(received::set);
        byte[] message = {1, 2, 3};
        assertTrue(diagnostics.sendControl(message));
        assertSame(message, received.get());
    }

    @Test void connectionStagesOnlyReportTransitions() {
        var diagnostics = new BotSessionDiagnostics(UUID.randomUUID(), UUID.randomUUID(), "TestBot", "test", 0);
        assertFalse(diagnostics.noteConnectionStage("DIRECT_RESOLVE_TARGET"));
        assertTrue(diagnostics.noteConnectionStage("DIRECT_TCP_CONNECTING"));
        assertFalse(diagnostics.noteConnectionStage("DIRECT_TCP_CONNECTING"));
        assertTrue(diagnostics.noteConnectionStage("DIRECT_JOIN_GAME"));
    }
}
