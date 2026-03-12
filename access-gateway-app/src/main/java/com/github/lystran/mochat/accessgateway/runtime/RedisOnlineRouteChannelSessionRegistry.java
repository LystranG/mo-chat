package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ReplacedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionRouteWriteException;
import com.github.lystran.mochat.common.session.SessionRouteWriter;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import io.netty.channel.Channel;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RedisOnlineRouteChannelSessionRegistry implements SessionRouteWriter<Channel> {
    private static final String ROUTE_EPOCH_PLACEHOLDER = "__ROUTE_EPOCH__";
    private static final String WRITE_ROUTE_SCRIPT = """
        local previous = redis.call('GET', KEYS[1])
        local epoch = redis.call('INCR', KEYS[2])
        local payload = string.gsub(ARGV[2], '__ROUTE_EPOCH__', tostring(epoch), 1)
        redis.call('SET', KEYS[1], payload, 'EX', ARGV[1])
        redis.call('SET', KEYS[3] .. tostring(epoch), previous or '', 'EX', ARGV[1])
        return tostring(epoch) .. '\\n' .. (previous or '')
        """;
    private static final String CLEAR_ROUTE_SCRIPT = """
        local payload = redis.call('GET', KEYS[1])
        if not payload then
          return 0
        end
        local values = {}
        for entry in string.gmatch(payload, '([^;]+)') do
          local separator = string.find(entry, '=')
          if separator then
            local key = string.sub(entry, 1, separator - 1)
            local value = string.sub(entry, separator + 1)
            values[key] = value
          end
        end
        if values['gatewayPod'] ~= ARGV[1] then
          return 0
        end
        if values['connectionId'] ~= ARGV[2] then
          return 0
        end
        if values['sessionId'] ~= ARGV[3] then
          return 0
        end
        if values['sessionVersion'] ~= ARGV[4] then
          return 0
        end
        if values['routeEpoch'] ~= ARGV[5] then
          return 0
        end
        redis.call('DEL', KEYS[1])
        return 1
        """;
    private static final String RENEW_ROUTE_SCRIPT = """
        local payload = redis.call('GET', KEYS[1])
        if not payload then
          return 0
        end
        local values = {}
        for entry in string.gmatch(payload, '([^;]+)') do
          local separator = string.find(entry, '=')
          if separator then
            local key = string.sub(entry, 1, separator - 1)
            local value = string.sub(entry, separator + 1)
            values[key] = value
          end
        end
        if values['gatewayPod'] ~= ARGV[1] then
          return 0
        end
        if values['connectionId'] ~= ARGV[2] then
          return 0
        end
        if values['sessionId'] ~= ARGV[3] then
          return 0
        end
        if values['sessionVersion'] ~= ARGV[4] then
          return 0
        end
        if values['routeEpoch'] ~= ARGV[5] then
          return 0
        end
        redis.call('SET', KEYS[1], ARGV[6], 'EX', ARGV[7])
        return 1
        """;

    private final RedisCommands<String, String> redisCommands;
    private final String gatewayPod;
    private final Duration leaseDuration;
    private final Clock clock;
    private final LocalGatewayConnectionDirectory localGatewayConnectionDirectory;

    public RedisOnlineRouteChannelSessionRegistry(
        RedisCommands<String, String> redisCommands,
        String gatewayPod,
        Duration leaseDuration
    ) {
        this(redisCommands, gatewayPod, leaseDuration, Clock.systemUTC(), null);
    }

    RedisOnlineRouteChannelSessionRegistry(
        RedisCommands<String, String> redisCommands,
        String gatewayPod,
        Duration leaseDuration,
        LocalGatewayConnectionDirectory localGatewayConnectionDirectory
    ) {
        this(redisCommands, gatewayPod, leaseDuration, Clock.systemUTC(), localGatewayConnectionDirectory);
    }

    RedisOnlineRouteChannelSessionRegistry(
        RedisCommands<String, String> redisCommands,
        String gatewayPod,
        Duration leaseDuration,
        Clock clock
    ) {
        this(redisCommands, gatewayPod, leaseDuration, clock, null);
    }

    RedisOnlineRouteChannelSessionRegistry(
        RedisCommands<String, String> redisCommands,
        String gatewayPod,
        Duration leaseDuration,
        Clock clock,
        LocalGatewayConnectionDirectory localGatewayConnectionDirectory
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.gatewayPod = normalizeGatewayPod(gatewayPod);
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.localGatewayConnectionDirectory = localGatewayConnectionDirectory;
    }

    @Override
    public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
        Objects.requireNonNull(resolvedSession, "resolvedSession");
        Objects.requireNonNull(channelRef, "channelRef");

        OnlineRouteRecord routeRecord = buildRouteRecord(resolvedSession, channelRef);
        String routeKey = routeKey(resolvedSession.userId());
        String evalResult;
        try {
            evalResult = redisCommands.eval(
                WRITE_ROUTE_SCRIPT,
                ScriptOutputType.VALUE,
                new String[]{routeKey, routeEpochKey(resolvedSession.userId()), replacedRouteKeyPrefix(resolvedSession.userId())},
                Long.toString(routeRecord.leaseDurationSeconds()),
                serializeRouteRecord(routeRecord)
            );
        } catch (RuntimeException evalFailure) {
            PersistedSessionRoute confirmedRoute = confirmPersistedRoute(routeKey, routeRecord, resolvedSession.userId());
            if (confirmedRoute != null) {
                return confirmedRoute;
            }
            throw SessionRouteWriteException.outcomeUnknown("Unable to confirm online route persistence outcome", evalFailure);
        }
        PersistedRouteWriteResult persistedRouteWriteResult = parseWriteResult(evalResult);
        if (persistedRouteWriteResult.routeEpoch() <= 0L) {
            throw SessionRouteWriteException.definitiveFailure("Failed to persist online route");
        }
        PersistedSessionRoute persistedSessionRoute = new PersistedSessionRoute(
            persistedRouteWriteResult.routeEpoch(),
            persistedRouteWriteResult.replacedRoute()
        );
        kickLocalReplacedConnection(resolvedSession, persistedSessionRoute.replacedRoute());
        return persistedSessionRoute;
    }

    @Override
    public boolean clearRoute(ResolvedSession resolvedSession, Channel channelRef, PersistedSessionRoute persistedRoute) {
        Objects.requireNonNull(resolvedSession, "resolvedSession");
        Objects.requireNonNull(channelRef, "channelRef");
        Objects.requireNonNull(persistedRoute, "persistedRoute");
        if (persistedRoute.routeEpoch() <= 0L) {
            return false;
        }

        Long cleared = redisCommands.eval(
            CLEAR_ROUTE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[]{routeKey(resolvedSession.userId())},
            encodeString(gatewayPod),
            encodeString(channelRef.id().asLongText()),
            encodeString(resolvedSession.sessionId()),
            Long.toString(resolvedSession.sessionVersion()),
            Long.toString(persistedRoute.routeEpoch())
        );
        return cleared != null && cleared > 0L;
    }

    @Override
    public boolean renewRoute(ResolvedSession resolvedSession, Channel channelRef, PersistedSessionRoute persistedRoute) {
        Objects.requireNonNull(resolvedSession, "resolvedSession");
        Objects.requireNonNull(channelRef, "channelRef");
        Objects.requireNonNull(persistedRoute, "persistedRoute");
        if (persistedRoute.routeEpoch() <= 0L) {
            return true;
        }

        OnlineRouteRecord renewedRouteRecord = buildRouteRecord(
            resolvedSession,
            channelRef,
            Long.toString(persistedRoute.routeEpoch())
        );
        Long renewed = redisCommands.eval(
            RENEW_ROUTE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[]{routeKey(resolvedSession.userId())},
            encodeString(gatewayPod),
            encodeString(channelRef.id().asLongText()),
            encodeString(resolvedSession.sessionId()),
            Long.toString(resolvedSession.sessionVersion()),
            Long.toString(persistedRoute.routeEpoch()),
            serializeRouteRecord(renewedRouteRecord),
            Long.toString(renewedRouteRecord.leaseDurationSeconds())
        );
        return renewed != null && renewed > 0L;
    }

    static Map<String, String> deserializeRouteRecord(String payload) {
        Map<String, String> values = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return values;
        }
        for (String entry : payload.split(";")) {
            int separatorIndex = entry.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = entry.substring(0, separatorIndex);
            String value = entry.substring(separatorIndex + 1);
            if ("gatewayPod".equals(key) || "connectionId".equals(key) || "sessionId".equals(key)) {
                values.put(key, decodeString(value));
            } else {
                values.put(key, value);
            }
        }
        return values;
    }

    private OnlineRouteRecord buildRouteRecord(ResolvedSession resolvedSession, Channel channelRef) {
        return buildRouteRecord(resolvedSession, channelRef, ROUTE_EPOCH_PLACEHOLDER);
    }

    private OnlineRouteRecord buildRouteRecord(ResolvedSession resolvedSession, Channel channelRef, String routeEpoch) {
        long leaseDurationSeconds = Math.max(1L, leaseDuration.toSeconds());
        long now = clock.millis();
        return new OnlineRouteRecord(
            gatewayPod,
            channelRef.id().asLongText(),
            resolvedSession.sessionId(),
            resolvedSession.sessionVersion(),
            routeEpoch,
            leaseDurationSeconds,
            now + leaseDuration.toMillis()
        );
    }

    private PersistedSessionRoute confirmPersistedRoute(String routeKey, OnlineRouteRecord expectedRoute, long userId) {
        try {
            Map<String, String> persistedRoute = deserializeRouteRecord(redisCommands.get(routeKey));
            if (!Objects.equals(expectedRoute.gatewayPod(), persistedRoute.get("gatewayPod"))) {
                return null;
            }
            if (!Objects.equals(expectedRoute.connectionId(), persistedRoute.get("connectionId"))) {
                return null;
            }
            if (!Objects.equals(expectedRoute.sessionId(), persistedRoute.get("sessionId"))) {
                return null;
            }
            if (!Objects.equals(Long.toString(expectedRoute.sessionVersion()), persistedRoute.get("sessionVersion"))) {
                return null;
            }
            if (!Objects.equals(Long.toString(expectedRoute.leaseDurationSeconds()), persistedRoute.get("leaseDurationSeconds"))) {
                return null;
            }
            if (!Objects.equals(
                Long.toString(expectedRoute.leaseExpiresAtEpochMilli()),
                persistedRoute.get("leaseExpiresAtEpochMilli")
            )) {
                return null;
            }
            String routeEpoch = persistedRoute.get("routeEpoch");
            if (routeEpoch == null || routeEpoch.isBlank()) {
                return null;
            }
            long parsedRouteEpoch = Long.parseLong(routeEpoch);
            if (parsedRouteEpoch <= 0L) {
                return null;
            }
            ReplacedSessionRoute replacedRoute = null;
            try {
                replacedRoute = parseReplacedRoute(redisCommands.get(replacedRouteKey(userId, parsedRouteEpoch)));
            } catch (RuntimeException ignored) {
                replacedRoute = null;
            }
            return new PersistedSessionRoute(parsedRouteEpoch, replacedRoute);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private PersistedRouteWriteResult parseWriteResult(String evalResult) {
        if (evalResult == null || evalResult.isBlank()) {
            return new PersistedRouteWriteResult(0L, null);
        }
        int separator = evalResult.indexOf('\n');
        String epochValue = separator >= 0 ? evalResult.substring(0, separator) : evalResult;
        long routeEpoch = Long.parseLong(epochValue);
        String previousPayload = separator >= 0 ? evalResult.substring(separator + 1) : "";
        return new PersistedRouteWriteResult(
            routeEpoch,
            previousPayload.isBlank() ? null : parseReplacedRoute(previousPayload)
        );
    }

    private ReplacedSessionRoute parseReplacedRoute(String payload) {
        Map<String, String> values = deserializeRouteRecord(payload);
        String gatewayPod = values.get("gatewayPod");
        String connectionId = values.get("connectionId");
        String sessionId = values.get("sessionId");
        if (gatewayPod == null || connectionId == null || sessionId == null) {
            return null;
        }
        String sessionVersion = values.get("sessionVersion");
        String routeEpoch = values.get("routeEpoch");
        if (sessionVersion == null || routeEpoch == null) {
            return null;
        }
        return new ReplacedSessionRoute(
            gatewayPod,
            connectionId,
            sessionId,
            Long.parseLong(sessionVersion),
            Long.parseLong(routeEpoch)
        );
    }

    private void kickLocalReplacedConnection(ResolvedSession resolvedSession, ReplacedSessionRoute replacedRoute) {
        if (localGatewayConnectionDirectory == null || replacedRoute == null) {
            return;
        }
        if (!gatewayPod.equals(replacedRoute.gatewayPod())) {
            return;
        }
        localGatewayConnectionDirectory.kickConnection(
            resolvedSession.userId(),
            replacedRoute.connectionId(),
            replacedRoute.sessionVersion(),
            replacedRoute.routeEpoch(),
            "replaced_by_new_bind"
        );
    }

    private static String serializeRouteRecord(OnlineRouteRecord routeRecord) {
        return String.join(";",
            "gatewayPod=" + encodeString(routeRecord.gatewayPod()),
            "connectionId=" + encodeString(routeRecord.connectionId()),
            "sessionId=" + encodeString(routeRecord.sessionId()),
            "sessionVersion=" + routeRecord.sessionVersion(),
            "routeEpoch=" + routeRecord.routeEpoch(),
            "leaseDurationSeconds=" + routeRecord.leaseDurationSeconds(),
            "leaseExpiresAtEpochMilli=" + routeRecord.leaseExpiresAtEpochMilli()
        );
    }

    private static String routeKey(long userId) {
        return "online:user:" + userId;
    }

    private static String routeEpochKey(long userId) {
        return "online:user:" + userId + ":route-epoch";
    }

    private static String replacedRouteKeyPrefix(long userId) {
        return "online:user:" + userId + ":replaced-route:";
    }

    private static String replacedRouteKey(long userId, long routeEpoch) {
        return replacedRouteKeyPrefix(userId) + routeEpoch;
    }

    private static String normalizeGatewayPod(String gatewayPod) {
        if (gatewayPod == null || gatewayPod.isBlank()) {
            return "access-gateway-local";
        }
        return gatewayPod;
    }

    private static String encodeString(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeString(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private record OnlineRouteRecord(
        String gatewayPod,
        String connectionId,
        String sessionId,
        long sessionVersion,
        String routeEpoch,
        long leaseDurationSeconds,
        long leaseExpiresAtEpochMilli
    ) {
    }

    private record PersistedRouteWriteResult(long routeEpoch, ReplacedSessionRoute replacedRoute) {
    }
}
