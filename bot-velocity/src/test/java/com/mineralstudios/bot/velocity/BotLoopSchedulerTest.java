package com.mineralstudios.bot.velocity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotLoopSchedulerTest {
    private BotLoopScheduler scheduler;

    @AfterEach
    void closeScheduler() {
        if (scheduler != null) {
            scheduler.closeGracefully(Duration.ofSeconds(1L));
        }
    }

    @Test
    void oneBotLoopNeverReenters() throws Exception {
        scheduler = new BotLoopScheduler(4, 2);
        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maximumConcurrentCalls = new AtomicInteger();
        CountDownLatch calls = new CountDownLatch(15);
        BotLoopScheduler.LoopHandle handle = handle();

        scheduler.schedule(handle, () -> {
            int active = activeCalls.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(2L);
                calls.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                activeCalls.decrementAndGet();
            }
        });

        assertTrue(calls.await(2L, TimeUnit.SECONDS));
        scheduler.stop(handle, false, () -> { });
        assertEquals(1, maximumConcurrentCalls.get());
    }

    @Test
    void oneHundredBotsStayWithinConfiguredWorkerCount() throws Exception {
        int workers = 3;
        scheduler = new BotLoopScheduler(workers, 2);
        CountDownLatch allBotsRan = new CountDownLatch(100);
        Set<String> workerNames = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < 100; ++i) {
            BotLoopScheduler.LoopHandle handle = handle();
            AtomicBoolean firstCall = new AtomicBoolean(true);
            scheduler.schedule(handle, () -> {
                workerNames.add(Thread.currentThread().getName());
                if (firstCall.compareAndSet(true, false)) {
                    allBotsRan.countDown();
                }
            });
        }

        assertTrue(allBotsRan.await(3L, TimeUnit.SECONDS));
        assertEquals(workers, scheduler.loopWorkerCount());
        assertTrue(workerNames.size() <= workers);
    }

    @Test
    void blockedBotDoesNotStopOthersAndTriggersWatchdog() throws Exception {
        scheduler = new BotLoopScheduler(
                2,
                2,
                1L,
                TimeUnit.MILLISECONDS.toNanos(80L),
                10L);
        CountDownLatch stalled = new CountDownLatch(1);
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicInteger healthyCalls = new AtomicInteger();

        BotLoopScheduler.LoopHandle blocked = scheduler.createHandle(
                UUID.randomUUID(),
                (uuid, duration) -> stalled.countDown(),
                (uuid, failure) -> { });
        BotLoopScheduler.LoopHandle healthy = handle();
        scheduler.schedule(blocked, () -> {
            blockerEntered.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        scheduler.schedule(healthy, healthyCalls::incrementAndGet);

        assertTrue(blockerEntered.await(1L, TimeUnit.SECONDS));
        assertTrue(stalled.await(1L, TimeUnit.SECONDS));
        assertTrue(healthyCalls.get() > 5);
        scheduler.stop(blocked, true, releaseBlocker::countDown);
    }

    @Test
    void cancellationRemovesTaskAndPreventsFurtherCalls() throws Exception {
        scheduler = new BotLoopScheduler(2, 2);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstCall = new CountDownLatch(1);
        CountDownLatch finalized = new CountDownLatch(1);
        BotLoopScheduler.LoopHandle handle = handle();
        scheduler.schedule(handle, () -> {
            calls.incrementAndGet();
            firstCall.countDown();
        });

        assertTrue(firstCall.await(1L, TimeUnit.SECONDS));
        scheduler.stop(handle, false, finalized::countDown);
        assertTrue(finalized.await(1L, TimeUnit.SECONDS));
        int callsAfterStop = calls.get();
        Thread.sleep(30L);

        assertEquals(callsAfterStop, calls.get());
        assertEquals(0, scheduler.registeredLoopCount());
        assertEquals(0, scheduler.queuedLoopTaskCount());
    }

    @Test
    void loopFailureIsReportedAndDoesNotSilentlyCancelTheTask() throws Exception {
        scheduler = new BotLoopScheduler(1, 1);
        CountDownLatch failureReported = new CountDownLatch(1);
        CountDownLatch laterCall = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BotLoopScheduler.LoopHandle handle = scheduler.createHandle(
                UUID.randomUUID(),
                (uuid, duration) -> { },
                (uuid, failure) -> failureReported.countDown());
        scheduler.schedule(handle, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("test failure");
            }
            laterCall.countDown();
        });

        assertTrue(failureReported.await(1L, TimeUnit.SECONDS));
        assertTrue(laterCall.await(1L, TimeUnit.SECONDS));
        assertFalse(handle.isStopping());
    }

    private BotLoopScheduler.LoopHandle handle() {
        return scheduler.createHandle(
                UUID.randomUUID(),
                (uuid, duration) -> { },
                (uuid, failure) -> { });
    }
}
