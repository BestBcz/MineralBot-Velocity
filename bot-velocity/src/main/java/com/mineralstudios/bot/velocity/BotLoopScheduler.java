package com.mineralstudios.bot.velocity;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

final class BotLoopScheduler implements AutoCloseable {

    static final long PUMP_DELAY_MILLIS = 1L;
    static final long STALL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
    static final long WATCHDOG_INTERVAL_MILLIS = 1_000L;
    private static final int STARTUP_QUEUE_CAPACITY = 256;

    private final ScheduledThreadPoolExecutor loopExecutor;
    private final ThreadPoolExecutor startupExecutor;
    private final ScheduledThreadPoolExecutor watchdogExecutor;
    private final long pumpDelayMillis;
    private final long stallTimeoutNanos;
    private final ConcurrentHashMap<UUID, LoopHandle> handles = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    BotLoopScheduler(int loopWorkers, int startupWorkers) {
        this(
                loopWorkers,
                startupWorkers,
                PUMP_DELAY_MILLIS,
                STALL_TIMEOUT_NANOS,
                WATCHDOG_INTERVAL_MILLIS);
    }

    BotLoopScheduler(
            int loopWorkers,
            int startupWorkers,
            long pumpDelayMillis,
            long stallTimeoutNanos,
            long watchdogIntervalMillis
    ) {
        if (loopWorkers < 1) {
            throw new IllegalArgumentException("loopWorkers must be positive");
        }
        if (startupWorkers < 1) {
            throw new IllegalArgumentException("startupWorkers must be positive");
        }
        if (pumpDelayMillis < 1L || stallTimeoutNanos < 1L || watchdogIntervalMillis < 1L) {
            throw new IllegalArgumentException("scheduler intervals must be positive");
        }

        this.pumpDelayMillis = pumpDelayMillis;
        this.stallTimeoutNanos = stallTimeoutNanos;

        this.loopExecutor = new ScheduledThreadPoolExecutor(
                loopWorkers,
                namedDaemonFactory("MineralBot-GameLoop"));
        this.loopExecutor.setRemoveOnCancelPolicy(true);
        this.loopExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.loopExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        this.startupExecutor = new ThreadPoolExecutor(
                startupWorkers,
                startupWorkers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(STARTUP_QUEUE_CAPACITY),
                namedDaemonFactory("MineralBot-Startup"),
                new ThreadPoolExecutor.AbortPolicy());

        this.watchdogExecutor = new ScheduledThreadPoolExecutor(
                1,
                namedDaemonFactory("MineralBot-Watchdog"));
        this.watchdogExecutor.setRemoveOnCancelPolicy(true);
        this.watchdogExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.watchdogExecutor.scheduleWithFixedDelay(
                this::checkForStalls,
                watchdogIntervalMillis,
                watchdogIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    LoopHandle createHandle(
            UUID botUuid,
            BiConsumer<UUID, Long> stallListener,
            BiConsumer<UUID, Throwable> failureListener
    ) {
        if (closed.get()) {
            throw new RejectedExecutionException("Bot loop scheduler is closed");
        }

        LoopHandle handle = new LoopHandle(botUuid, stallListener, failureListener);
        LoopHandle previous = handles.putIfAbsent(botUuid, handle);
        if (previous != null) {
            throw new IllegalStateException("Bot loop already registered for " + botUuid);
        }
        return handle;
    }

    void submitStartup(LoopHandle handle, Runnable startupTask) {
        requireOwned(handle);
        startupExecutor.execute(() -> handle.runSerialized(startupTask, false));
    }

    void schedule(LoopHandle handle, Runnable loopTask) {
        requireOwned(handle);
        if (handle.stopping.get()) {
            return;
        }

        ScheduledFuture<?> future = loopExecutor.scheduleWithFixedDelay(
                () -> handle.runLoop(loopTask),
                pumpDelayMillis,
                pumpDelayMillis,
                TimeUnit.MILLISECONDS);
        if (!handle.installFuture(future)) {
            future.cancel(false);
        }
    }

    void stop(LoopHandle handle, boolean interrupt, Runnable finalizer) {
        if (handle == null || !handle.stopping.compareAndSet(false, true)) {
            return;
        }

        handles.remove(handle.botUuid, handle);
        ScheduledFuture<?> future = handle.future;
        if (future != null) {
            future.cancel(interrupt);
        }

        Runnable serializedFinalizer = () -> handle.runSerialized(finalizer, true);
        try {
            startupExecutor.execute(serializedFinalizer);
        } catch (RejectedExecutionException ignored) {
            serializedFinalizer.run();
        }
    }

    int loopWorkerCount() {
        return loopExecutor.getCorePoolSize();
    }

    int registeredLoopCount() {
        return handles.size();
    }

    int queuedLoopTaskCount() {
        return loopExecutor.getQueue().size();
    }

    private void requireOwned(LoopHandle handle) {
        if (handle == null || handles.get(handle.botUuid) != handle || closed.get()) {
            throw new RejectedExecutionException("Bot loop handle is not active");
        }
    }

    private void checkForStalls() {
        long now = System.nanoTime();
        for (LoopHandle handle : handles.values()) {
            long startedAt = handle.loopStartedAtNanos;
            if (!handle.inLoop || startedAt <= 0L || now - startedAt < stallTimeoutNanos) {
                continue;
            }
            if (handle.stallReported.compareAndSet(false, true)) {
                try {
                    handle.stallListener.accept(handle.botUuid, now - startedAt);
                } catch (Throwable failure) {
                    handle.reportFailure(failure);
                }
            }
        }
    }

    void closeGracefully(Duration timeout) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        watchdogExecutor.shutdownNow();
        for (LoopHandle handle : handles.values()) {
            stop(handle, true, () -> { });
        }
        loopExecutor.shutdownNow();
        startupExecutor.shutdown();

        long timeoutMillis = Math.max(1L, timeout.toMillis());
        try {
            if (!startupExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                startupExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            startupExecutor.shutdownNow();
        }
    }

    @Override
    public void close() {
        closeGracefully(Duration.ofSeconds(5L));
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    static final class LoopHandle {
        private final UUID botUuid;
        private final BiConsumer<UUID, Long> stallListener;
        private final BiConsumer<UUID, Throwable> failureListener;
        private final ReentrantLock lifecycleLock = new ReentrantLock();
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final AtomicBoolean stallReported = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;
        private volatile boolean inLoop;
        private volatile long loopStartedAtNanos;

        private LoopHandle(
                UUID botUuid,
                BiConsumer<UUID, Long> stallListener,
                BiConsumer<UUID, Throwable> failureListener
        ) {
            this.botUuid = botUuid;
            this.stallListener = stallListener;
            this.failureListener = failureListener;
        }

        UUID botUuid() {
            return botUuid;
        }

        boolean isStopping() {
            return stopping.get();
        }

        private boolean installFuture(ScheduledFuture<?> scheduledFuture) {
            if (stopping.get() || future != null) {
                return false;
            }
            future = scheduledFuture;
            return true;
        }

        private void runLoop(Runnable task) {
            if (stopping.get()) {
                return;
            }

            lifecycleLock.lock();
            try {
                if (stopping.get()) {
                    return;
                }
                inLoop = true;
                loopStartedAtNanos = System.nanoTime();
                stallReported.set(false);
                task.run();
            } catch (Throwable failure) {
                reportFailure(failure);
            } finally {
                inLoop = false;
                loopStartedAtNanos = 0L;
                lifecycleLock.unlock();
            }
        }

        private void runSerialized(Runnable task, boolean allowWhenStopping) {
            lifecycleLock.lock();
            try {
                if (!allowWhenStopping && stopping.get()) {
                    return;
                }
                task.run();
            } catch (Throwable failure) {
                reportFailure(failure);
            } finally {
                lifecycleLock.unlock();
            }
        }

        private void reportFailure(Throwable failure) {
            try {
                failureListener.accept(botUuid, failure);
            } catch (Throwable ignored) {
                // A failure callback must never kill a shared scheduler worker.
            }
        }
    }
}
