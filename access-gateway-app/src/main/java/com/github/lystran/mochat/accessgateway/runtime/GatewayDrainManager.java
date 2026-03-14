package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.connection.GatewayDrainState;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GatewayDrainManager implements GatewayDrainState, AutoCloseable {
    private final LocalGatewayConnectionDirectory localGatewayConnectionDirectory;
    private final ScheduledExecutorService scheduler;
    private final Duration gracePeriod;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final CompletableFuture<Void> drainCompletion = new CompletableFuture<>();

    private volatile ScheduledFuture<?> gracePeriodClosure;

    public GatewayDrainManager(
        LocalGatewayConnectionDirectory localGatewayConnectionDirectory,
        ScheduledExecutorService scheduler,
        Duration gracePeriod
    ) {
        this.localGatewayConnectionDirectory = Objects.requireNonNull(
            localGatewayConnectionDirectory,
            "localGatewayConnectionDirectory"
        );
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.gracePeriod = Objects.requireNonNull(gracePeriod, "gracePeriod");
    }

    @Override
    public boolean isDraining() {
        return draining.get();
    }

    public void startDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        gracePeriodClosure = scheduler.schedule(
            this::closeAndComplete,
            Math.max(0L, gracePeriod.toMillis()),
            TimeUnit.MILLISECONDS
        );
    }

    public int closeBoundConnectionsNow() {
        ScheduledFuture<?> scheduledClosure = gracePeriodClosure;
        if (scheduledClosure != null) {
            scheduledClosure.cancel(false);
        }
        return closeAndComplete();
    }

    public void awaitDrainCompletion() {
        if (!draining.get()) {
            return;
        }
        try {
            drainCompletion.get(Math.max(1L, gracePeriod.toMillis()) + 1_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting gateway drain completion", interruptedException);
        } catch (ExecutionException executionException) {
            throw new IllegalStateException("Gateway drain failed", executionException.getCause());
        } catch (TimeoutException timeoutException) {
            throw new IllegalStateException("Timed out waiting for gateway drain completion", timeoutException);
        }
    }

    private int completeDrainWindow() {
        return localGatewayConnectionDirectory.closeBoundConnections();
    }

    private int closeAndComplete() {
        try {
            int closedConnections = completeDrainWindow();
            drainCompletion.complete(null);
            return closedConnections;
        } catch (RuntimeException runtimeException) {
            drainCompletion.completeExceptionally(runtimeException);
            throw runtimeException;
        }
    }

    @Override
    public void close() {
        ScheduledFuture<?> scheduledClosure = gracePeriodClosure;
        if (scheduledClosure != null) {
            scheduledClosure.cancel(false);
        }
    }
}
