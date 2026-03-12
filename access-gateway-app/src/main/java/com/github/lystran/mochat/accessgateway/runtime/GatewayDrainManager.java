package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.connection.GatewayDrainState;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GatewayDrainManager implements GatewayDrainState, AutoCloseable {
    private final LocalGatewayConnectionDirectory localGatewayConnectionDirectory;
    private final ScheduledExecutorService scheduler;
    private final Duration gracePeriod;
    private final AtomicBoolean draining = new AtomicBoolean(false);

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
            this::closeBoundConnectionsNow,
            Math.max(0L, gracePeriod.toMillis()),
            TimeUnit.MILLISECONDS
        );
    }

    public int closeBoundConnectionsNow() {
        return localGatewayConnectionDirectory.closeBoundConnections();
    }

    @Override
    public void close() {
        ScheduledFuture<?> scheduledClosure = gracePeriodClosure;
        if (scheduledClosure != null) {
            scheduledClosure.cancel(false);
        }
    }
}
