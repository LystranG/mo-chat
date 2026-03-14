package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.session.InMemoryChannelSessionRegistry;
import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ReplacedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionReplacementHandler;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.connection.HeartbeatHandler;
import com.github.lystran.mochat.connection.InboundRouterHandler;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionResponse;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionResolutionStatus;
import com.github.lystran.mochat.protocol.internal.common.v1.SessionPrincipal;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGatewayOnlineRouteBindingTest {
    @Test
    void successfulBindWritesRedisOnlineRouteRecord() throws Exception {
        long beforeBindAt = System.currentTimeMillis();
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-online-route",
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "mochat.access-gateway.tcp.enabled", true,
            "mochat.access-gateway.route.gateway-pod", "gateway-pod-a"
        ))) {
            SessionBindingHandler sessionBindingHandler = context.getBean(SessionBindingHandler.class);
            RecordingRedisStore redisStore = context.getBean(RecordingRedisStore.class);
            RecordingInboundHandler downstream = new RecordingInboundHandler();
            EmbeddedChannel channel = new EmbeddedChannel(sessionBindingHandler, downstream);

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            long afterBindAt = System.currentTimeMillis();
            assertEquals(1, downstream.messageCount);

            Map<String, String> routeRecord = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
                redisStore.value("online:user:42")
            );
            assertNotNull(routeRecord);
            assertEquals("gateway-pod-a", routeRecord.get("gatewayPod"));
            assertEquals(channel.id().asLongText(), routeRecord.get("connectionId"));
            assertEquals("active:42:7", routeRecord.get("sessionId"));
            assertEquals("7", routeRecord.get("sessionVersion"));
            assertTrue(Long.parseLong(routeRecord.get("routeEpoch")) > 0L);
            long leaseDurationSeconds = Long.parseLong(routeRecord.get("leaseDurationSeconds"));
            assertTrue(leaseDurationSeconds > 0L);

            long leaseExpiresAt = Long.parseLong(routeRecord.get("leaseExpiresAtEpochMilli"));
            assertTrue(leaseExpiresAt >= beforeBindAt + (leaseDurationSeconds * 1_000L));
            assertTrue(leaseExpiresAt <= afterBindAt + (leaseDurationSeconds * 1_000L) + 2_000L);
            assertEquals(leaseDurationSeconds, redisStore.ttlSeconds("online:user:42"));
        }
    }

    @Test
    void kubernetesPodMetadataBecomesDefaultGatewayIdentityWhenPresent() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-online-route",
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "mochat.access-gateway.tcp.enabled", true,
            "mochat.runtime.pod.name", "access-gateway-0",
            "mochat.runtime.pod.namespace", "chat",
            "mochat.access-gateway.route.gateway-pod", "access-gateway-local"
        ))) {
            SessionBindingHandler sessionBindingHandler = context.getBean(SessionBindingHandler.class);
            RecordingRedisStore redisStore = context.getBean(RecordingRedisStore.class);
            EmbeddedChannel channel = new EmbeddedChannel(sessionBindingHandler, new RecordingInboundHandler());

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            Map<String, String> routeRecord = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
                redisStore.value("online:user:42")
            );
            assertNotNull(routeRecord);
            assertEquals("access-gateway-0", routeRecord.get("gatewayPod"));
        }
    }

    @Test
    void newerBindTriggersKickFlowForPreviousLocalConnectionAndPersistsNewActiveRoute() throws Exception {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        RecordingKickDirectory userChannelDirectory = new RecordingKickDirectory(new InMemoryUserChannelDirectory());
        GatewayRouteReplacementHandler delegateReplacementHandler = new GatewayRouteReplacementHandler(
            "gateway-pod-a",
            userChannelDirectory,
            targetAddress -> request -> {
                throw new AssertionError("unexpected remote kick");
            },
            gatewayPod -> null
        );
        AtomicInteger replacementCount = new AtomicInteger();
        AtomicReference<PersistedSessionRoute> replacementRoute = new AtomicReference<>();
        SessionReplacementHandler sessionReplacementHandler = (newBinding, persistedRoute) -> {
            replacementCount.incrementAndGet();
            replacementRoute.set(persistedRoute);
            delegateReplacementHandler.handleReplacement(newBinding, persistedRoute);
        };
        SessionBindingHandler sessionBindingHandler = new SessionBindingHandler(
            new SessionResolver() {
                @Override
                public java.util.Optional<ResolvedSession> resolveSession(String sessionId) {
                    return switch (sessionId) {
                        case "active:42:7" -> java.util.Optional.of(new ResolvedSession("active:42:7", 42L, 7L));
                        case "active:42:8" -> java.util.Optional.of(new ResolvedSession("active:42:8", 42L, 8L));
                        default -> java.util.Optional.empty();
                    };
                }

                @Override
                public java.util.Optional<Long> resolveUserId(String sessionId) {
                    return resolveSession(sessionId).map(ResolvedSession::userId);
                }
            },
            new InMemoryChannelSessionRegistry<>(userChannelDirectory),
            new RedisOnlineRouteChannelSessionRegistry(
                redisStore.commands(),
                "gateway-pod-a",
                java.time.Duration.ofSeconds(60),
                userChannelDirectory
            ),
            sessionReplacementHandler,
            null,
            64
        );

        RecordingInboundHandler oldDownstream = new RecordingInboundHandler();
        RecordingInboundHandler newDownstream = new RecordingInboundHandler();
        EmbeddedChannel oldChannel = new EmbeddedChannel(sessionBindingHandler, oldDownstream);
        EmbeddedChannel newChannel = new EmbeddedChannel(sessionBindingHandler, newDownstream);

        oldChannel.writeInbound(privateMessage("active:42:7", 200L, 88L));
        waitForPendingTasks(oldChannel);

        Map<String, String> firstRoute = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
            redisStore.value("online:user:42")
        );
        long firstRouteEpoch = Long.parseLong(firstRoute.get("routeEpoch"));
        assertTrue(oldChannel.isOpen());
        assertTrue(userChannelDirectory.findLocalConnectionState(42L, oldChannel.id().asLongText()).isPresent());

        newChannel.writeInbound(privateMessage("active:42:8", 201L, 89L));
        waitForPendingTasks(newChannel);
        waitForPendingTasks(oldChannel);

        Map<String, String> currentRoute = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
            redisStore.value("online:user:42")
        );
        assertEquals(1, replacementCount.get());
        assertEquals("gateway-pod-a", replacementRoute.get().replacedRoute().gatewayPod());
        assertEquals(oldChannel.id().asLongText(), replacementRoute.get().replacedRoute().connectionId());
        assertTrue(userChannelDirectory.kickCount() >= 1);
        assertEquals(oldChannel.id().asLongText(), userChannelDirectory.lastKick().connectionId());
        assertEquals(42L, userChannelDirectory.lastKick().userId());
        assertEquals(7L, userChannelDirectory.lastKick().sessionVersion());
        assertEquals(firstRouteEpoch, userChannelDirectory.lastKick().expectedRouteEpoch());
        assertEquals(1, oldDownstream.messageCount);
        assertEquals(1, newDownstream.messageCount);
        assertEquals(newChannel.id().asLongText(), currentRoute.get("connectionId"));
        assertEquals("active:42:8", currentRoute.get("sessionId"));
        assertEquals("8", currentRoute.get("sessionVersion"));
        assertTrue(Long.parseLong(currentRoute.get("routeEpoch")) > firstRouteEpoch);
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextWiredReplacementHandlerClosesMatchingLocalConnection() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-online-route",
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "mochat.access-gateway.tcp.enabled", true,
            "mochat.access-gateway.route.gateway-pod", "gateway-pod-a"
        ))) {
            UserChannelDirectory<io.netty.channel.Channel> userChannelDirectory =
                (UserChannelDirectory<io.netty.channel.Channel>) context.getBean(UserChannelDirectory.class);
            SessionReplacementHandler sessionReplacementHandler = context.getBean(SessionReplacementHandler.class);
            EmbeddedChannel oldChannel = new EmbeddedChannel();
            oldChannel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).set("active:42:7");
            oldChannel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).set(42L);
            oldChannel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).set(7L);
            oldChannel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).set(11L);
            oldChannel.attr(SessionBindingHandler.ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(Boolean.TRUE);
            userChannelDirectory.bind(42L, oldChannel);

            sessionReplacementHandler.handleReplacement(
                new ResolvedSession("active:42:8", 42L, 8L),
                new PersistedSessionRoute(12L, new ReplacedSessionRoute(
                    "gateway-pod-a",
                    oldChannel.id().asLongText(),
                    "active:42:7",
                    7L,
                    11L
                ))
            );
            waitForPendingTasks(oldChannel);

            assertFalse(oldChannel.isOpen());
        }
    }

    @Test
    void recordingRedisStoreReturnsReplacedRouteMetadataOnOverwrite() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        RedisOnlineRouteChannelSessionRegistry routeWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            java.time.Duration.ofSeconds(60)
        );
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();

        PersistedSessionRoute firstRoute = routeWriter.writeRoute(new ResolvedSession("active:42:7", 42L, 7L), firstChannel);
        PersistedSessionRoute secondRoute = routeWriter.writeRoute(new ResolvedSession("active:42:8", 42L, 8L), secondChannel);

        assertEquals(firstRoute.routeEpoch(), secondRoute.replacedRoute().routeEpoch());
        assertEquals(firstChannel.id().asLongText(), secondRoute.replacedRoute().connectionId());
        assertEquals("active:42:7", secondRoute.replacedRoute().sessionId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmationReadFailureClearsLocalBindAndClosesChannel() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-online-route",
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "mochat.access-gateway.tcp.enabled", true,
            "mochat.access-gateway.route.gateway-pod", "gateway-pod-a"
        ))) {
            SessionBindingHandler sessionBindingHandler = context.getBean(SessionBindingHandler.class);
            RecordingRedisStore redisStore = context.getBean(RecordingRedisStore.class);
            redisStore.failEval = true;
            RecordingInboundHandler downstream = new RecordingInboundHandler();
            EmbeddedChannel channel = new EmbeddedChannel(sessionBindingHandler, downstream);
            UserChannelDirectory<io.netty.channel.Channel> userChannelDirectory =
                (UserChannelDirectory<io.netty.channel.Channel>) context.getBean(UserChannelDirectory.class);

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            assertEquals(List.of("eval", "get"), redisStore.calls);
            assertEquals(0, downstream.messageCount);
            assertTrue(userChannelDirectory.find(42L).isEmpty());
            assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
            assertFalse(channel.isOpen());
            assertErrorResponse(channel, ErrorCode.INTERNAL_ERROR.code());
            assertFalse(redisStore.values.containsKey("online:user:42"));
        }
    }

    @Test
    void heartbeatAckRenewsOwnedRouteLease() throws Exception {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-03-11T00:00:00Z"));
        SessionBindingHandler sessionBindingHandler = new SessionBindingHandler(
            sessionResolverForActiveSessions(),
            new InMemoryChannelSessionRegistry<>(new InMemoryUserChannelDirectory()),
            new RedisOnlineRouteChannelSessionRegistry(
                redisStore.commands(),
                "gateway-pod-a",
                java.time.Duration.ofSeconds(60),
                clock
            ),
            SessionReplacementHandler.noop(),
            null,
            64
        );
        EmbeddedChannel channel = new EmbeddedChannel(
            new HeartbeatHandler(1, 5),
            sessionBindingHandler,
            new RecordingInboundHandler()
        );

        channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
        waitForPendingTasks(channel);

        Map<String, String> routeBeforeHeartbeat = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
            redisStore.value("online:user:42")
        );
        long leaseExpiresAtBeforeHeartbeat = Long.parseLong(routeBeforeHeartbeat.get("leaseExpiresAtEpochMilli"));

        clock.advanceMillis(20L);
        channel.writeInbound(clientHeartbeat());
        waitForPendingTasks(channel);

        Map<String, String> routeAfterHeartbeat = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
            redisStore.value("online:user:42")
        );
        long leaseExpiresAtAfterHeartbeat = Long.parseLong(routeAfterHeartbeat.get("leaseExpiresAtEpochMilli"));

        assertTrue(channel.isOpen());
        assertTrue(leaseExpiresAtAfterHeartbeat > leaseExpiresAtBeforeHeartbeat);
        assertEquals(60L, redisStore.ttlSeconds("online:user:42"));
    }

    @Test
    void staleRouteObservedDuringHeartbeatSelfKillsReplacedConnection() throws Exception {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        InMemoryUserChannelDirectory userChannelDirectory = new InMemoryUserChannelDirectory();
        SessionBindingHandler sessionBindingHandler = new SessionBindingHandler(
            sessionResolverForActiveSessions(),
            new InMemoryChannelSessionRegistry<>(userChannelDirectory),
            new RedisOnlineRouteChannelSessionRegistry(
                redisStore.commands(),
                "gateway-pod-a",
                java.time.Duration.ofSeconds(60)
            ),
            SessionReplacementHandler.noop(),
            null,
            64
        );
        EmbeddedChannel oldChannel = new EmbeddedChannel(
            new HeartbeatHandler(1, 5),
            sessionBindingHandler,
            new RecordingInboundHandler()
        );
        EmbeddedChannel newChannel = new EmbeddedChannel(
            new HeartbeatHandler(1, 5),
            sessionBindingHandler,
            new RecordingInboundHandler()
        );

        oldChannel.writeInbound(privateMessage("active:42:7", 200L, 88L));
        waitForPendingTasks(oldChannel);
        assertTrue(oldChannel.isOpen());

        newChannel.writeInbound(privateMessage("active:42:8", 201L, 89L));
        waitForPendingTasks(newChannel);
        assertTrue(oldChannel.isOpen());

        oldChannel.writeInbound(clientHeartbeat());
        waitForPendingTasks(oldChannel);

        Map<String, String> currentRoute = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
            redisStore.value("online:user:42")
        );
        assertFalse(oldChannel.isOpen());
        assertEquals(newChannel.id().asLongText(), currentRoute.get("connectionId"));
        assertEquals("active:42:8", currentRoute.get("sessionId"));
    }

    @Test
    void newerBindOnOtherGatewayLeavesOldOwnerAliveUntilHeartbeatThenSelfKills() throws Exception {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        InMemoryUserChannelDirectory gatewayAUserChannelDirectory = new InMemoryUserChannelDirectory();
        InMemoryUserChannelDirectory gatewayBUserChannelDirectory = new InMemoryUserChannelDirectory();
        SessionBindingHandler gatewayASessionBindingHandler = new SessionBindingHandler(
            sessionResolverForActiveSessions(),
            new InMemoryChannelSessionRegistry<>(gatewayAUserChannelDirectory),
            new RedisOnlineRouteChannelSessionRegistry(
                redisStore.commands(),
                "gateway-pod-a",
                java.time.Duration.ofSeconds(60)
            ),
            SessionReplacementHandler.noop(),
            null,
            64
        );
        SessionBindingHandler gatewayBSessionBindingHandler = new SessionBindingHandler(
            sessionResolverForActiveSessions(),
            new InMemoryChannelSessionRegistry<>(gatewayBUserChannelDirectory),
            new RedisOnlineRouteChannelSessionRegistry(
                redisStore.commands(),
                "gateway-pod-b",
                java.time.Duration.ofSeconds(60)
            ),
            SessionReplacementHandler.noop(),
            null,
            64
        );
        EmbeddedChannel oldChannel = new EmbeddedChannel(
            new HeartbeatHandler(1, 5),
            gatewayASessionBindingHandler,
            new RecordingInboundHandler()
        );
        EmbeddedChannel newChannel = new EmbeddedChannel(
            new HeartbeatHandler(1, 5),
            gatewayBSessionBindingHandler,
            new RecordingInboundHandler()
        );

        oldChannel.writeInbound(privateMessage("active:42:7", 200L, 88L));
        waitForPendingTasks(oldChannel);
        assertTrue(oldChannel.isOpen());

        newChannel.writeInbound(privateMessage("active:42:8", 201L, 89L));
        waitForPendingTasks(newChannel);

        Map<String, String> replacedRoute = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
            redisStore.value("online:user:42")
        );
        assertTrue(oldChannel.isOpen());
        assertTrue(newChannel.isOpen());
        assertEquals("gateway-pod-b", replacedRoute.get("gatewayPod"));
        assertEquals(newChannel.id().asLongText(), replacedRoute.get("connectionId"));
        assertEquals("active:42:8", replacedRoute.get("sessionId"));

        oldChannel.writeInbound(clientHeartbeat());
        waitForPendingTasks(oldChannel);

        Map<String, String> currentRoute = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
            redisStore.value("online:user:42")
        );
        assertFalse(oldChannel.isOpen());
        assertTrue(newChannel.isOpen());
        assertEquals("gateway-pod-b", currentRoute.get("gatewayPod"));
        assertEquals(newChannel.id().asLongText(), currentRoute.get("connectionId"));
        assertEquals("active:42:8", currentRoute.get("sessionId"));
    }

    @Test
    void heartbeatTimeoutClearsOwnedRouteBeforeClosingChannel() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        SessionBindingHandler sessionBindingHandler = new SessionBindingHandler(
            sessionResolverForActiveSessions(),
            new InMemoryChannelSessionRegistry<>(new InMemoryUserChannelDirectory()),
            new RedisOnlineRouteChannelSessionRegistry(
                redisStore.commands(),
                "gateway-pod-a",
                java.time.Duration.ofSeconds(60)
            ),
            SessionReplacementHandler.noop(),
            null,
            64
        );
        EmbeddedChannel channel = new EmbeddedChannel(
            new HeartbeatHandler(1, 1),
            sessionBindingHandler,
            new RecordingInboundHandler()
        );

        channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();
        assertNotNull(redisStore.value("online:user:42"));

        channel.advanceTimeBy(2, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertNull(redisStore.value("online:user:42"));
    }

    @Test
    void drainingGatewayRejectsNewBindOwnership() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-online-route",
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "mochat.access-gateway.tcp.enabled", true,
            "mochat.access-gateway.route.gateway-pod", "gateway-pod-a",
            "mochat.access-gateway.drain.enabled", true,
            "mochat.access-gateway.drain.grace-period", "30s"
        ))) {
            SessionBindingHandler sessionBindingHandler = context.getBean(SessionBindingHandler.class);
            RecordingRedisStore redisStore = context.getBean(RecordingRedisStore.class);
            RecordingInboundHandler downstream = new RecordingInboundHandler();
            EmbeddedChannel channel = new EmbeddedChannel(sessionBindingHandler, downstream);

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            assertFalse(channel.isOpen());
            assertEquals(0, downstream.messageCount);
            assertNull(redisStore.value("online:user:42"));
            assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
            assertErrorResponse(channel, ErrorCode.INTERNAL_ERROR.code());
        }
    }

    @Test
    void drainGraceExpirationClosesExistingBoundConnectionAndClearsRoute() throws Exception {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        InMemoryUserChannelDirectory userChannelDirectory = new InMemoryUserChannelDirectory();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            GatewayDrainManager drainManager = new GatewayDrainManager(
                userChannelDirectory,
                scheduler,
                java.time.Duration.ofSeconds(30)
            );
            RecordingInboundHandler downstream = new RecordingInboundHandler();
            SessionBindingHandler sessionBindingHandler = new SessionBindingHandler(
                sessionResolverForActiveSessions(),
                new InMemoryChannelSessionRegistry<>(userChannelDirectory),
                new RedisOnlineRouteChannelSessionRegistry(
                    redisStore.commands(),
                    "gateway-pod-a",
                    java.time.Duration.ofSeconds(60)
                ),
                SessionReplacementHandler.noop(),
                drainManager,
                null,
                64
            );
            EmbeddedChannel channel = new EmbeddedChannel(sessionBindingHandler, downstream);

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);
            assertNotNull(redisStore.value("online:user:42"));

            drainManager.startDrain();

            channel.writeInbound(privateMessage("active:42:7", 201L, 89L));
            waitForPendingTasks(channel);

            assertTrue(channel.isOpen());
            assertEquals(2, downstream.messageCount);
            assertNotNull(redisStore.value("online:user:42"));

            drainManager.closeBoundConnectionsNow();
            waitForPendingTasks(channel);

            assertFalse(channel.isOpen());
            assertNull(redisStore.value("online:user:42"));
        }
    }

    @Test
    void drainingGatewayRejectsNewBindButAllowsReconnectOnOtherGatewayAfterGrace() throws Exception {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        InMemoryUserChannelDirectory gatewayAUserChannelDirectory = new InMemoryUserChannelDirectory();
        InMemoryUserChannelDirectory gatewayBUserChannelDirectory = new InMemoryUserChannelDirectory();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            GatewayDrainManager drainManager = new GatewayDrainManager(
                gatewayAUserChannelDirectory,
                scheduler,
                java.time.Duration.ofSeconds(30)
            );
            SessionBindingHandler gatewayASessionBindingHandler = new SessionBindingHandler(
                sessionResolverForActiveSessions(),
                new InMemoryChannelSessionRegistry<>(gatewayAUserChannelDirectory),
                new RedisOnlineRouteChannelSessionRegistry(
                    redisStore.commands(),
                    "gateway-pod-a",
                    java.time.Duration.ofSeconds(60)
                ),
                SessionReplacementHandler.noop(),
                drainManager,
                null,
                64
            );
            SessionBindingHandler gatewayBSessionBindingHandler = new SessionBindingHandler(
                sessionResolverForActiveSessions(),
                new InMemoryChannelSessionRegistry<>(gatewayBUserChannelDirectory),
                new RedisOnlineRouteChannelSessionRegistry(
                    redisStore.commands(),
                    "gateway-pod-b",
                    java.time.Duration.ofSeconds(60)
                ),
                SessionReplacementHandler.noop(),
                null,
                64
            );
            RecordingInboundHandler oldDownstream = new RecordingInboundHandler();
            EmbeddedChannel oldChannel = new EmbeddedChannel(gatewayASessionBindingHandler, oldDownstream);

            oldChannel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(oldChannel);

            Map<String, String> routeBeforeDrain = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
                redisStore.value("online:user:42")
            );
            assertEquals("gateway-pod-a", routeBeforeDrain.get("gatewayPod"));
            assertTrue(oldChannel.isOpen());

            drainManager.startDrain();

            RecordingInboundHandler rejectedDownstream = new RecordingInboundHandler();
            EmbeddedChannel rejectedChannel = new EmbeddedChannel(gatewayASessionBindingHandler, rejectedDownstream);
            rejectedChannel.writeInbound(privateMessage("active:42:7", 201L, 89L));
            waitForPendingTasks(rejectedChannel);

            assertFalse(rejectedChannel.isOpen());
            assertEquals(0, rejectedDownstream.messageCount);
            assertErrorResponse(rejectedChannel, ErrorCode.INTERNAL_ERROR.code());
            assertTrue(oldChannel.isOpen());

            drainManager.closeBoundConnectionsNow();
            waitForPendingTasks(oldChannel);

            assertFalse(oldChannel.isOpen());
            assertNull(redisStore.value("online:user:42"));

            RecordingInboundHandler reconnectDownstream = new RecordingInboundHandler();
            EmbeddedChannel reconnectedChannel = new EmbeddedChannel(gatewayBSessionBindingHandler, reconnectDownstream);
            reconnectedChannel.writeInbound(privateMessage("active:42:7", 202L, 90L));
            waitForPendingTasks(reconnectedChannel);

            Map<String, String> routeAfterReconnect = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(
                redisStore.value("online:user:42")
            );
            assertTrue(reconnectedChannel.isOpen());
            assertEquals(1, reconnectDownstream.messageCount);
            assertEquals("gateway-pod-b", routeAfterReconnect.get("gatewayPod"));
            assertEquals(reconnectedChannel.id().asLongText(), routeAfterReconnect.get("connectionId"));
            assertEquals("active:42:7", routeAfterReconnect.get("sessionId"));
            assertEquals("7", routeAfterReconnect.get("sessionVersion"));
        }
    }

    private static InboundRouterHandler.InboundMessage privateMessage(String sessionId, long conversationId, long toUid) {
        return new InboundRouterHandler.InboundMessage(
            com.github.lystran.mochat.protocol.MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId(sessionId)
                .setClientMsgId(1001L)
                .setConversationId(conversationId)
                .setToUid(toUid)
                .setNonce(ByteString.copyFrom(new byte[12]))
                .setCiphertext(ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        );
    }

    private static InboundRouterHandler.InboundMessage clientHeartbeat() {
        return new InboundRouterHandler.InboundMessage(
            MsgType.CLIENT_HEARTBEAT,
            SerializerType.PROTOBUF,
            new byte[0]
        );
    }

    private static SessionResolver sessionResolverForActiveSessions() {
        return new SessionResolver() {
            @Override
            public java.util.Optional<ResolvedSession> resolveSession(String sessionId) {
                return switch (sessionId) {
                    case "active:42:7" -> java.util.Optional.of(new ResolvedSession("active:42:7", 42L, 7L));
                    case "active:42:8" -> java.util.Optional.of(new ResolvedSession("active:42:8", 42L, 8L));
                    default -> java.util.Optional.empty();
                };
            }

            @Override
            public java.util.Optional<Long> resolveUserId(String sessionId) {
                return resolveSession(sessionId).map(ResolvedSession::userId);
            }
        };
    }

    private static String encodeString(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Factory
    @Requires(property = "spec.name", value = "access-gateway-online-route")
    static final class TestFactory {
        @Singleton
        EventBus eventBus() {
            return new InProcessEventBus();
        }

        @Singleton
        OfflineQueue offlineQueue() {
            return new OfflineQueue() {
                @Override
                public void enqueue(long userId, String payload, int maxQueueSize) {
                }

                @Override
                public java.util.List<String> drain(long userId, int maxItems) {
                    return java.util.List.of();
                }
            };
        }

        @Singleton
        SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub sessionAuthorityApiBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) Channel channel
        ) {
            return SessionAuthorityApiGrpc.newBlockingStub(channel);
        }

        @Singleton
        RecordingRedisStore recordingRedisStore() {
            return new RecordingRedisStore();
        }

        @Singleton
        RedisCommands<String, String> redisCommands(RecordingRedisStore store) {
            return store.commands();
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = "access-gateway-online-route")
    static final class TestSessionAuthorityService extends SessionAuthorityApiGrpc.SessionAuthorityApiImplBase {
        @Override
        public void resolveSession(ResolveSessionRequest request, StreamObserver<ResolveSessionResponse> responseObserver) {
            ResolveSessionResponse response = switch (request.getSessionId()) {
                case "active:42:7" -> ResolveSessionResponse.newBuilder()
                    .setStatus(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE)
                    .setPrincipal(SessionPrincipal.newBuilder()
                        .setSessionId("active:42:7")
                        .setUserId(42L)
                        .setSessionVersion(7L)
                        .build())
                    .build();
                case "active:42:8" -> ResolveSessionResponse.newBuilder()
                    .setStatus(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE)
                    .setPrincipal(SessionPrincipal.newBuilder()
                        .setSessionId("active:42:8")
                        .setUserId(42L)
                        .setSessionVersion(8L)
                        .build())
                    .build();
                default -> ResolveSessionResponse.newBuilder()
                    .setStatus(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_INVALID)
                    .build();
            };
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    static final class RecordingRedisStore {
        private final List<String> calls = new ArrayList<>();
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, Long> ttlSeconds = new ConcurrentHashMap<>();
        private final Map<String, Long> routeEpochs = new ConcurrentHashMap<>();
        private boolean failEval;
        private boolean persistRouteBeforeEvalFailure;

        RedisCommands<String, String> commands() {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[]{RedisCommands.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "eval" -> {
                            calls.add("eval");
                            String script = (String) args[0];
                            String[] keys = (String[]) args[2];
                            Object[] valuesArgs = args[3] instanceof Object[] array ? array : new Object[]{args[3]};
                            if (script.contains("redis.call('SET'") && keys.length == 3) {
                                String routeKey = keys[0];
                                String routeEpochKey = keys[1];
                                String replacedRoutePrefix = keys[2];
                                long seconds = Long.parseLong((String) valuesArgs[0]);
                                String payloadTemplate = (String) valuesArgs[1];
                                String previousPayload = values.get(routeKey);
                                if (failEval) {
                                    if (persistRouteBeforeEvalFailure) {
                                        long routeEpoch = routeEpochs.merge(routeEpochKey, 1L, Long::sum);
                                        values.put(routeKey, payloadTemplate.replace("__ROUTE_EPOCH__", Long.toString(routeEpoch)));
                                        ttlSeconds.put(routeKey, seconds);
                                        values.put(replacedRoutePrefix + routeEpoch, previousPayload == null ? "" : previousPayload);
                                    }
                                    throw new IllegalStateException("redis unavailable");
                                }
                                long routeEpoch = routeEpochs.merge(routeEpochKey, 1L, Long::sum);
                                values.put(routeKey, payloadTemplate.replace("__ROUTE_EPOCH__", Long.toString(routeEpoch)));
                                ttlSeconds.put(routeKey, seconds);
                                values.put(replacedRoutePrefix + routeEpoch, previousPayload == null ? "" : previousPayload);
                                yield routeEpoch + "\n" + (previousPayload == null ? "" : previousPayload);
                            }
                            if (script.contains("redis.call('SET'") && keys.length == 1) {
                                String routeKey = keys[0];
                                Map<String, String> persistedRoute = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(values.get(routeKey));
                                if (persistedRoute.isEmpty()) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[0], encodeString(persistedRoute.get("gatewayPod")))) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[1], encodeString(persistedRoute.get("connectionId")))) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[2], encodeString(persistedRoute.get("sessionId")))) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[3], persistedRoute.get("sessionVersion"))) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[4], persistedRoute.get("routeEpoch"))) {
                                    yield 0L;
                                }
                                values.put(routeKey, (String) valuesArgs[5]);
                                ttlSeconds.put(routeKey, Long.parseLong((String) valuesArgs[6]));
                                yield 1L;
                            }
                            if (script.contains("redis.call('DEL'")) {
                                String routeKey = keys[0];
                                Map<String, String> persistedRoute = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(values.get(routeKey));
                                if (persistedRoute.isEmpty()) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[0], encodeString(persistedRoute.get("gatewayPod")))) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[1], encodeString(persistedRoute.get("connectionId")))) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[2], encodeString(persistedRoute.get("sessionId")))) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[3], persistedRoute.get("sessionVersion"))) {
                                    yield 0L;
                                }
                                if (!java.util.Objects.equals(valuesArgs[4], persistedRoute.get("routeEpoch"))) {
                                    yield 0L;
                                }
                                values.remove(routeKey);
                                ttlSeconds.remove(routeKey);
                                yield 1L;
                            }
                            throw new UnsupportedOperationException(script);
                        }
                        case "get" -> {
                            calls.add("get");
                            yield values.get((String) args[0]);
                        }
                        case "toString" -> "RecordingRedisCommands";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }
            );
        }

        String value(String key) {
            return values.get(key);
        }

        Long ttlSeconds(String key) {
            return ttlSeconds.get(key);
        }
    }

    private static void assertErrorResponse(EmbeddedChannel channel, int expectedErrorCode) throws Exception {
        ByteBuf frame = channel.readOutbound();
        assertNotNull(frame);
        try {
            assertEquals(FrameConstants.HEADER_LENGTH + frame.getInt(FrameConstants.BODY_LENGTH_OFFSET), frame.readableBytes());
            assertEquals(0x4D4F4348, frame.readInt());
            assertEquals(FrameConstants.PROTOCOL_VERSION, frame.readUnsignedByte());
            assertEquals(MsgType.ERROR_RESPONSE.code(), frame.readUnsignedByte());
            assertEquals(SerializerType.PROTOBUF.code(), frame.readUnsignedByte());
            int bodyLength = frame.readInt();
            byte[] body = new byte[bodyLength];
            frame.readBytes(body);
            Mochat.ErrorResponse error = Mochat.ErrorResponse.parseFrom(body);
            assertEquals(expectedErrorCode, error.getErrorCode());
        } finally {
            frame.release();
        }
        assertNull(channel.readOutbound());
    }

    private static final class RecordingInboundHandler extends ChannelInboundHandlerAdapter {
        private int messageCount;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            messageCount++;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }
    }

    private static final class RecordingKickDirectory implements UserChannelDirectory<io.netty.channel.Channel>, LocalGatewayConnectionDirectory {
        private final InMemoryUserChannelDirectory delegate;
        private int kickCount;
        private KickAttempt lastKick;

        private RecordingKickDirectory(InMemoryUserChannelDirectory delegate) {
            this.delegate = delegate;
        }

        @Override
        public void bind(long userId, io.netty.channel.Channel channelRef) {
            delegate.bind(userId, channelRef);
        }

        @Override
        public java.util.Optional<io.netty.channel.Channel> find(long userId) {
            return delegate.find(userId);
        }

        @Override
        public boolean unbind(long userId, io.netty.channel.Channel channelRef) {
            return delegate.unbind(userId, channelRef);
        }

        @Override
        public boolean kickConnection(long userId, String connectionId, long sessionVersion, long expectedRouteEpoch, String reason) {
            kickCount++;
            lastKick = new KickAttempt(userId, connectionId, sessionVersion, expectedRouteEpoch, reason);
            return true;
        }

        @Override
        public java.util.Optional<LocalConnectionStateSnapshot> findLocalConnectionState(long userId, String connectionId) {
            return delegate.findLocalConnectionState(userId, connectionId);
        }

        @Override
        public int closeBoundConnections() {
            return delegate.closeBoundConnections();
        }

        private int kickCount() {
            return kickCount;
        }

        private KickAttempt lastKick() {
            return lastKick;
        }
    }

    private record KickAttempt(long userId, String connectionId, long sessionVersion, long expectedRouteEpoch, String reason) {
    }

    private static void waitForPendingTasks(EmbeddedChannel channel) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();
    }
}
