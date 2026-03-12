package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ReplacedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionRouteWriteException;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionResponse;
import io.lettuce.core.api.sync.RedisCommands;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisOnlineRouteChannelSessionRegistryTest {
    @Test
    void routePayloadAndLeaseArePersistedAtomicallyWithSingleRedisScriptCall() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        RedisOnlineRouteChannelSessionRegistry routeWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );

        PersistedSessionRoute route = routeWriter.writeRoute(resolvedSession(), new EmbeddedChannel());

        assertTrue(route.routeEpoch() > 0L);
        assertEquals(List.of("eval"), redisStore.calls);
        assertEquals(60L, redisStore.ttlSeconds("online:user:42"));
        Map<String, String> payload = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(redisStore.value("online:user:42"));
        assertEquals("gateway-pod-a", payload.get("gatewayPod"));
        assertEquals("active:42:7", payload.get("sessionId"));
        assertEquals("7", payload.get("sessionVersion"));
        assertEquals(Long.toString(route.routeEpoch()), payload.get("routeEpoch"));
    }

    @Test
    void routeEpochMonotonicallyIncreasesAcrossWriterInstancesForSameUser() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        RedisOnlineRouteChannelSessionRegistry firstWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );
        RedisOnlineRouteChannelSessionRegistry secondWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-b",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:01Z"), ZoneOffset.UTC)
        );

        PersistedSessionRoute firstRoute = firstWriter.writeRoute(resolvedSession(), new EmbeddedChannel());
        PersistedSessionRoute secondRoute = secondWriter.writeRoute(resolvedSession(), new EmbeddedChannel());

        assertTrue(secondRoute.routeEpoch() > firstRoute.routeEpoch());
        Map<String, String> payload = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(redisStore.value("online:user:42"));
        assertEquals("gateway-pod-b", payload.get("gatewayPod"));
        assertEquals(Long.toString(secondRoute.routeEpoch()), payload.get("routeEpoch"));
    }

    @Test
    void writeRouteReturnsReplacedRouteMetadataWhenOverwritingExistingOwner() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        RedisOnlineRouteChannelSessionRegistry firstWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );
        RedisOnlineRouteChannelSessionRegistry secondWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:01Z"), ZoneOffset.UTC)
        );
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();

        PersistedSessionRoute firstRoute = firstWriter.writeRoute(resolvedSession(), firstChannel);
        PersistedSessionRoute secondRoute = secondWriter.writeRoute(
            new ResolvedSession("active:42:8", 42L, 8L),
            secondChannel
        );

        ReplacedSessionRoute replacedRoute = secondRoute.replacedRoute();
        assertEquals(firstRoute.routeEpoch(), replacedRoute.routeEpoch());
        assertEquals("gateway-pod-a", replacedRoute.gatewayPod());
        assertEquals(firstChannel.id().asLongText(), replacedRoute.connectionId());
        assertEquals("active:42:7", replacedRoute.sessionId());
        assertEquals(7L, replacedRoute.sessionVersion());
    }

    @Test
    void renewRouteRefreshesLeaseWhenPersistedRouteStillMatchesOwner() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        EmbeddedChannel channel = new EmbeddedChannel();
        RedisOnlineRouteChannelSessionRegistry firstWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );

        PersistedSessionRoute firstRoute = firstWriter.writeRoute(resolvedSession(), channel);
        long originalLeaseExpiresAt = Long.parseLong(
            RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(redisStore.value("online:user:42"))
                .get("leaseExpiresAtEpochMilli")
        );

        RedisOnlineRouteChannelSessionRegistry renewalWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:30Z"), ZoneOffset.UTC)
        );

        boolean renewed = renewalWriter.renewRoute(resolvedSession(), channel, firstRoute);

        assertTrue(renewed);
        Map<String, String> payload = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(redisStore.value("online:user:42"));
        assertEquals(Long.toString(firstRoute.routeEpoch()), payload.get("routeEpoch"));
        assertEquals("active:42:7", payload.get("sessionId"));
        assertEquals(60L, redisStore.ttlSeconds("online:user:42"));
        assertTrue(Long.parseLong(payload.get("leaseExpiresAtEpochMilli")) > originalLeaseExpiresAt);
    }

    @Test
    void renewRouteReturnsFalseWhenRouteOwnershipHasMovedToNewerBind() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();
        RedisOnlineRouteChannelSessionRegistry firstWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );
        PersistedSessionRoute firstRoute = firstWriter.writeRoute(resolvedSession(), firstChannel);
        RedisOnlineRouteChannelSessionRegistry secondWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:01Z"), ZoneOffset.UTC)
        );
        PersistedSessionRoute secondRoute = secondWriter.writeRoute(
            new ResolvedSession("active:42:8", 42L, 8L),
            secondChannel
        );

        boolean renewed = firstWriter.renewRoute(resolvedSession(), firstChannel, firstRoute);

        assertFalse(renewed);
        Map<String, String> payload = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(redisStore.value("online:user:42"));
        assertEquals(Long.toString(secondRoute.routeEpoch()), payload.get("routeEpoch"));
        assertEquals("active:42:8", payload.get("sessionId"));
        assertEquals("8", payload.get("sessionVersion"));
    }

    @Test
    void evalFailureTreatsMatchingConfirmedRouteAsSuccessfulWrite() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        redisStore.failEval = true;
        redisStore.persistRouteBeforeEvalFailure = true;
        RedisOnlineRouteChannelSessionRegistry routeWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );

        PersistedSessionRoute route = routeWriter.writeRoute(resolvedSession(), new EmbeddedChannel());

        assertTrue(route.routeEpoch() > 0L);
        assertEquals(List.of("eval", "get", "get"), redisStore.calls);
        Map<String, String> payload = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(redisStore.value("online:user:42"));
        assertEquals(Long.toString(route.routeEpoch()), payload.get("routeEpoch"));
        assertEquals("gateway-pod-a", payload.get("gatewayPod"));
        assertEquals("active:42:7", payload.get("sessionId"));
        assertEquals("7", payload.get("sessionVersion"));
    }

    @Test
    void evalFailureTreatsMatchingConfirmedOverwriteAsSuccessfulWriteAndPreservesReplacedRoute() throws Exception {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        RedisOnlineRouteChannelSessionRegistry routeWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );
        InMemoryUserChannelDirectory directory = new InMemoryUserChannelDirectory();
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        firstChannel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).set("active:42:7");
        firstChannel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).set(42L);
        firstChannel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).set(7L);
        firstChannel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).set(1L);
        firstChannel.attr(SessionBindingHandler.ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(Boolean.TRUE);
        directory.bind(42L, firstChannel);
        PersistedSessionRoute firstRoute = routeWriter.writeRoute(resolvedSession(), firstChannel);
        firstChannel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).set(firstRoute.routeEpoch());

        redisStore.failEval = true;
        redisStore.persistRouteBeforeEvalFailure = true;

        PersistedSessionRoute confirmedRoute = routeWriter.writeRoute(
            new ResolvedSession("active:42:8", 42L, 8L),
            new EmbeddedChannel()
        );

        assertEquals(List.of("eval", "eval", "get", "get"), redisStore.calls);
        assertEquals(firstRoute.routeEpoch(), confirmedRoute.replacedRoute().routeEpoch());
        assertEquals(firstChannel.id().asLongText(), confirmedRoute.replacedRoute().connectionId());
        assertEquals("active:42:7", confirmedRoute.replacedRoute().sessionId());
        assertEquals(7L, confirmedRoute.replacedRoute().sessionVersion());

        GatewayRouteReplacementHandler replacementHandler = new GatewayRouteReplacementHandler(
            "gateway-pod-a",
            directory,
            targetAddress -> request -> KickConnectionResponse.getDefaultInstance(),
            Map.of()
        );
        replacementHandler.handleReplacement(new ResolvedSession("active:42:8", 42L, 8L), confirmedRoute);
        waitForPendingTasks(firstChannel);

        assertFalse(firstChannel.isOpen());
    }

    @Test
    void confirmedWriteStillSucceedsWhenReplacementMetadataReadFails() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        RedisOnlineRouteChannelSessionRegistry routeWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );
        PersistedSessionRoute firstRoute = routeWriter.writeRoute(resolvedSession(), new EmbeddedChannel());

        redisStore.failEval = true;
        redisStore.persistRouteBeforeEvalFailure = true;
        redisStore.failReplacementMetadataGet = true;

        PersistedSessionRoute confirmedRoute = routeWriter.writeRoute(
            new ResolvedSession("active:42:8", 42L, 8L),
            new EmbeddedChannel()
        );

        assertTrue(confirmedRoute.routeEpoch() > firstRoute.routeEpoch());
        assertEquals(List.of("eval", "eval", "get", "get"), redisStore.calls);
        assertEquals(null, confirmedRoute.replacedRoute());
    }

    @Test
    void evalFailureDoesNotTreatOldRouteWithMismatchedLeaseAsSuccessfulWrite() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        redisStore.failEval = true;
        EmbeddedChannel channel = new EmbeddedChannel();
        redisStore.preloadRoute(
            "online:user:42",
            routePayload(
                "gateway-pod-a",
                channel.id().asLongText(),
                "active:42:7",
                7L,
                9L,
                30L,
                Instant.parse("2026-03-10T13:59:30Z").toEpochMilli()
            ),
            30L
        );
        RedisOnlineRouteChannelSessionRegistry routeWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );

        SessionRouteWriteException exception = assertThrows(
            SessionRouteWriteException.class,
            () -> routeWriter.writeRoute(resolvedSession(), channel)
        );

        assertTrue(exception.outcomeUnknown());
        assertEquals(List.of("eval", "get"), redisStore.calls);
        Map<String, String> payload = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(redisStore.value("online:user:42"));
        assertEquals("9", payload.get("routeEpoch"));
        assertEquals("30", payload.get("leaseDurationSeconds"));
    }

    @Test
    void redisScriptFailureDoesNotLeaveRedisRouteRecord() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        redisStore.failEval = true;
        RedisOnlineRouteChannelSessionRegistry routeWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );

        SessionRouteWriteException exception = assertThrows(
            SessionRouteWriteException.class,
            () -> routeWriter.writeRoute(resolvedSession(), new EmbeddedChannel())
        );

        assertTrue(exception.outcomeUnknown());
        assertEquals(List.of("eval", "get"), redisStore.calls);
        assertFalse(redisStore.hasRoute("online:user:42"));
    }

    @Test
    void clearRouteRemovesMatchingPersistedRoute() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        RedisOnlineRouteChannelSessionRegistry routeWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );
        EmbeddedChannel channel = new EmbeddedChannel();

        PersistedSessionRoute route = routeWriter.writeRoute(resolvedSession(), channel);

        boolean cleared = routeWriter.clearRoute(resolvedSession(), channel, route);

        assertTrue(cleared);
        assertEquals(List.of("eval", "eval"), redisStore.calls);
        assertFalse(redisStore.hasRoute("online:user:42"));
    }

    @Test
    void clearRouteDoesNotDeleteNewerPersistedRoute() {
        RecordingRedisStore redisStore = new RecordingRedisStore();
        RedisOnlineRouteChannelSessionRegistry firstWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-a",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC)
        );
        RedisOnlineRouteChannelSessionRegistry secondWriter = new RedisOnlineRouteChannelSessionRegistry(
            redisStore.commands(),
            "gateway-pod-b",
            Duration.ofSeconds(60),
            Clock.fixed(Instant.parse("2026-03-10T14:00:01Z"), ZoneOffset.UTC)
        );
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();

        PersistedSessionRoute firstRoute = firstWriter.writeRoute(resolvedSession(), firstChannel);
        PersistedSessionRoute secondRoute = secondWriter.writeRoute(resolvedSession(), secondChannel);

        boolean cleared = firstWriter.clearRoute(resolvedSession(), firstChannel, firstRoute);

        assertFalse(cleared);
        assertTrue(redisStore.hasRoute("online:user:42"));
        Map<String, String> payload = RedisOnlineRouteChannelSessionRegistry.deserializeRouteRecord(redisStore.value("online:user:42"));
        assertEquals(Long.toString(secondRoute.routeEpoch()), payload.get("routeEpoch"));
        assertEquals("gateway-pod-b", payload.get("gatewayPod"));
    }

    private static ResolvedSession resolvedSession() {
        return new ResolvedSession("active:42:7", 42L, 7L);
    }

    private static String routePayload(
        String gatewayPod,
        String connectionId,
        String sessionId,
        long sessionVersion,
        long routeEpoch,
        long leaseDurationSeconds,
        long leaseExpiresAtEpochMilli
    ) {
        return String.join(";",
            "gatewayPod=" + encodeString(gatewayPod),
            "connectionId=" + encodeString(connectionId),
            "sessionId=" + encodeString(sessionId),
            "sessionVersion=" + sessionVersion,
            "routeEpoch=" + routeEpoch,
            "leaseDurationSeconds=" + leaseDurationSeconds,
            "leaseExpiresAtEpochMilli=" + leaseExpiresAtEpochMilli
        );
    }

    private static String encodeString(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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

    private static final class RecordingRedisStore {
        private final List<String> calls = new ArrayList<>();
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, Long> ttlSeconds = new ConcurrentHashMap<>();
        private final Map<String, Long> epochs = new ConcurrentHashMap<>();
        private boolean failEval;
        private boolean persistRouteBeforeEvalFailure;
        private boolean failReplacementMetadataGet;

        RedisCommands<String, String> commands() {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[]{RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "eval" -> {
                        calls.add("eval");
                        String script = (String) args[0];
                        String[] keys = (String[]) args[2];
                        Object[] valuesArgs = args[3] instanceof Object[] array ? array : new Object[]{args[3]};
                        if (script.contains("redis.call('SET'") && keys.length == 3) {
                            String routeKey = keys[0];
                            String epochKey = keys[1];
                            String replacedRoutePrefix = keys[2];
                            long seconds = Long.parseLong((String) valuesArgs[0]);
                            String payloadTemplate = (String) valuesArgs[1];
                            String previousPayload = values.get(routeKey);
                            if (failEval) {
                                if (persistRouteBeforeEvalFailure) {
                                    long epoch = epochs.merge(epochKey, 1L, Long::sum);
                                    String payload = payloadTemplate.replace("__ROUTE_EPOCH__", Long.toString(epoch));
                                    values.put(routeKey, payload);
                                    ttlSeconds.put(routeKey, seconds);
                                    values.put(replacedRoutePrefix + epoch, previousPayload == null ? "" : previousPayload);
                                }
                                throw new IllegalStateException("redis unavailable");
                            }
                            long epoch = epochs.merge(epochKey, 1L, Long::sum);
                            String payload = payloadTemplate.replace("__ROUTE_EPOCH__", Long.toString(epoch));
                            values.put(routeKey, payload);
                            ttlSeconds.put(routeKey, seconds);
                            values.put(replacedRoutePrefix + epoch, previousPayload == null ? "" : previousPayload);
                            yield epoch + "\n" + (previousPayload == null ? "" : previousPayload);
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
                        if (failReplacementMetadataGet && ((String) args[0]).contains(":replaced-route:")) {
                            throw new IllegalStateException("replacement metadata unavailable");
                        }
                        yield values.get((String) args[0]);
                    }
                    case "toString" -> "RecordingRedisCommands";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            );
        }

        boolean hasRoute(String key) {
            return values.containsKey(key) || ttlSeconds.containsKey(key);
        }

        String value(String key) {
            return values.get(key);
        }

        Long ttlSeconds(String key) {
            return ttlSeconds.get(key);
        }

        void preloadRoute(String key, String payload, long ttlSeconds) {
            values.put(key, payload);
            this.ttlSeconds.put(key, ttlSeconds);
        }
    }
}
