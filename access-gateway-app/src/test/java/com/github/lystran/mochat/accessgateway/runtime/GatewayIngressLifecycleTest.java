package com.github.lystran.mochat.accessgateway.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayIngressLifecycleTest {
    @Test
    void drainTransitionsGatewayOutOfReadinessWhileProcessRemainsLive() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        GatewayDrainManager drainManager = new GatewayDrainManager(new StubConnectionDirectory(), scheduler, Duration.ofSeconds(30));
        GatewayIngressLifecycle lifecycle = new GatewayIngressLifecycle(drainManager);
        try {
            assertTrue(lifecycle.isLive());
            assertTrue(lifecycle.isReady());

            lifecycle.startDrain();

            assertTrue(drainManager.isDraining());
            assertFalse(lifecycle.isReady());
            assertTrue(lifecycle.isLive());
        } finally {
            drainManager.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void awaitDrainCompletionWaitsForGraceWindowToCloseBoundConnections() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger closedConnections = new AtomicInteger();
        GatewayDrainManager drainManager = new GatewayDrainManager(
            new CountingConnectionDirectory(closedConnections),
            scheduler,
            Duration.ofMillis(25)
        );
        try {
            drainManager.startDrain();

            drainManager.awaitDrainCompletion();

            assertEquals(1, closedConnections.get());
        } finally {
            drainManager.close();
            scheduler.shutdownNow();
        }
    }

    private static final class StubConnectionDirectory implements LocalGatewayConnectionDirectory {
        @Override
        public boolean kickConnection(long userId, String connectionId, long sessionVersion, long expectedRouteEpoch, String reason) {
            return false;
        }

        @Override
        public Optional<LocalConnectionStateSnapshot> findLocalConnectionState(long userId, String connectionId) {
            return Optional.empty();
        }

        @Override
        public int closeBoundConnections() {
            return 0;
        }
    }

    private static final class CountingConnectionDirectory implements LocalGatewayConnectionDirectory {
        private final AtomicInteger closedConnections;

        private CountingConnectionDirectory(AtomicInteger closedConnections) {
            this.closedConnections = closedConnections;
        }

        @Override
        public boolean kickConnection(long userId, String connectionId, long sessionVersion, long expectedRouteEpoch, String reason) {
            return false;
        }

        @Override
        public Optional<LocalConnectionStateSnapshot> findLocalConnectionState(long userId, String connectionId) {
            return Optional.empty();
        }

        @Override
        public int closeBoundConnections() {
            return closedConnections.incrementAndGet();
        }
    }
}
