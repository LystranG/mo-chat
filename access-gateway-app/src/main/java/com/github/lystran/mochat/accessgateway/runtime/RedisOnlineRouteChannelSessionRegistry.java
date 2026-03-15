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

/**
 * 把“哪个网关正在负责这个用户的连接”写进 Redis，并负责续租和清理。
 */
public final class RedisOnlineRouteChannelSessionRegistry implements SessionRouteWriter<Channel> {
    private static final String ROUTE_EPOCH_PLACEHOLDER = "__ROUTE_EPOCH__";
    /**
     * 原子写入新路由、递增路由版本，并把被顶掉的旧路由另存一份，方便后续踢旧连接。
     */
    private static final String WRITE_ROUTE_SCRIPT = """
        local previous = redis.call('GET', KEYS[1])
        local epoch = redis.call('INCR', KEYS[2])
        local payload = string.gsub(ARGV[2], '__ROUTE_EPOCH__', tostring(epoch), 1)
        redis.call('SET', KEYS[1], payload, 'EX', ARGV[1])
        redis.call('SET', KEYS[3] .. tostring(epoch), previous or '', 'EX', ARGV[1])
        return tostring(epoch) .. '\\n' .. (previous or '')
        """;
    /**
     * 只有 Redis 里现在仍然是这条连接时，才允许清掉在线路由。
     */
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
    /**
     * 只有当前网关仍然持有这条路由时，才续长租期；一旦发现已被替换就返回失败。
     */
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
    /**
     * 当前网关实例名字，会被写进在线路由，让别的服务知道消息该发到哪个网关。
     */
    private final String gatewayPod;
    /**
     * 在线路由的租期长度，心跳续租时会不断往后延。
     */
    private final Duration leaseDuration;
    private final Clock clock;
    /**
     * 当被顶掉的旧连接还在本机时，立即按本地目录把它关掉。
     */
    private final LocalGatewayConnectionDirectory localGatewayConnectionDirectory;

    /**
     * 使用系统时钟创建在线路由写入器。
     */
    public RedisOnlineRouteChannelSessionRegistry(
        RedisCommands<String, String> redisCommands,
        String gatewayPod,
        Duration leaseDuration
    ) {
        this(redisCommands, gatewayPod, leaseDuration, Clock.systemUTC(), null);
    }

    /**
     * 使用系统时钟，并允许在本机直接踢被替换的旧连接。
     */
    RedisOnlineRouteChannelSessionRegistry(
        RedisCommands<String, String> redisCommands,
        String gatewayPod,
        Duration leaseDuration,
        LocalGatewayConnectionDirectory localGatewayConnectionDirectory
    ) {
        this(redisCommands, gatewayPod, leaseDuration, Clock.systemUTC(), localGatewayConnectionDirectory);
    }

    /**
     * 供测试注入时钟。
     */
    RedisOnlineRouteChannelSessionRegistry(
        RedisCommands<String, String> redisCommands,
        String gatewayPod,
        Duration leaseDuration,
        Clock clock
    ) {
        this(redisCommands, gatewayPod, leaseDuration, clock, null);
    }

    /**
     * 完整构造器，支持自定义时钟和本地连接目录。
     */
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
    /**
     * 写入一条新的在线路由；如果用户已经在别处绑定，会顺手拿到被替换掉的旧路由。
     */
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
            // Redis 脚本超时并不一定代表没写成功，所以先回头确认一次，避免把已经生效的绑定当成失败。
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
    /**
     * 只在 Redis 里仍然是当前这条连接时，才清理在线路由。
     */
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
    /**
     * 心跳续租时只延长自己仍然持有的那条路由，发现已经被顶掉就返回 false。
     */
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

    /**
     * 把 Redis 里的路由字符串还原成键值对，便于做精确比较。
     */
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

    /**
     * 构造待写入 Redis 的在线路由记录，路由版本稍后由 Lua 脚本填入。
     */
    private OnlineRouteRecord buildRouteRecord(ResolvedSession resolvedSession, Channel channelRef) {
        return buildRouteRecord(resolvedSession, channelRef, ROUTE_EPOCH_PLACEHOLDER);
    }

    /**
     * 构造带指定路由版本的在线路由记录。
     */
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

    /**
     * 当脚本执行结果拿不到时，回头检查 Redis 里是否已经落下了我们期望的路由。
     */
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

    /**
     * 解析 Lua 脚本返回值，拿到新路由版本和被替换掉的旧路由。
     */
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

    /**
     * 把旧路由字符串转成结构化对象，后面好按连接精确踢旧连接。
     */
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

    /**
     * 如果被替换的旧连接就在本机，立即本地关掉，减少旧连接继续存活的窗口。
     */
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

    /**
     * 把在线路由编码成适合放进 Redis 的字符串。
     */
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

    /**
     * 生成某个用户的在线路由 Key。
     */
    private static String routeKey(long userId) {
        return "online:user:" + userId;
    }

    /**
     * 生成某个用户的路由版本计数 Key。
     */
    private static String routeEpochKey(long userId) {
        return "online:user:" + userId + ":route-epoch";
    }

    /**
     * 生成保存“被替换旧路由”的 Key 前缀。
     */
    private static String replacedRouteKeyPrefix(long userId) {
        return "online:user:" + userId + ":replaced-route:";
    }

    /**
     * 生成某次路由替换对应的旧路由 Key。
     */
    private static String replacedRouteKey(long userId, long routeEpoch) {
        return replacedRouteKeyPrefix(userId) + routeEpoch;
    }

    /**
     * 兜底生成网关名字，避免路由里出现空值。
     */
    private static String normalizeGatewayPod(String gatewayPod) {
        if (gatewayPod == null || gatewayPod.isBlank()) {
            return "access-gateway-local";
        }
        return gatewayPod;
    }

    /**
     * 对字符串字段做 URL 安全的 Base64 编码，避免分号和等号干扰路由格式。
     */
    private static String encodeString(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 还原路由字符串里经过 Base64 编码的字段。
     */
    private static String decodeString(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    /**
     * 一条准备写进 Redis 的在线路由记录。
     *
     * @param gatewayPod 负责这条连接的网关实例名字
     * @param connectionId 当前连接 ID
     * @param sessionId 当前会话 ID
     * @param sessionVersion 当前会话版本
     * @param routeEpoch 当前在线路由版本
     * @param leaseDurationSeconds 这条路由的租期秒数
     * @param leaseExpiresAtEpochMilli 这条路由预计过期时间
     */
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

    /**
     * Lua 写路由脚本的解析结果。
     *
     * @param routeEpoch 新写入路由的版本号
     * @param replacedRoute 这次写入顺手替换掉的旧路由
     */
    private record PersistedRouteWriteResult(long routeEpoch, ReplacedSessionRoute replacedRoute) {
    }
}
