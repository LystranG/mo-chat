package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 当前网关进程内的连接目录，既能按用户找连接，也能按连接 ID 做本地管理。
 */
final class InMemoryUserChannelDirectory implements UserChannelDirectory<Channel>, LocalGatewayConnectionDirectory {
    /**
     * 用用户 ID 找到当前用户在本机持有的连接。
     */
    private final ConcurrentMap<Long, Channel> channelsByUserId = new ConcurrentHashMap<>();
    /**
     * 用连接 ID 找到具体 Channel，便于跨网关通知后精确踢旧连接。
     */
    private final ConcurrentMap<String, Channel> channelsByConnectionId = new ConcurrentHashMap<>();

    @Override
    /**
     * 记录某个用户当前在本机对应的连接。
     */
    public void bind(long userId, Channel channelRef) {
        Channel nonNullChannel = Objects.requireNonNull(channelRef, "channelRef");
        channelsByUserId.put(userId, nonNullChannel);
        channelsByConnectionId.put(nonNullChannel.id().asLongText(), nonNullChannel);
    }

    @Override
    /**
     * 按用户 ID 查找本地连接。
     */
    public Optional<Channel> find(long userId) {
        return Optional.ofNullable(channelsByUserId.get(userId));
    }

    @Override
    /**
     * 只在目录里的记录仍然指向这条 Channel 时才解除绑定，避免误删别的连接。
     */
    public boolean unbind(long userId, Channel channelRef) {
        Channel nonNullChannel = Objects.requireNonNull(channelRef, "channelRef");
        boolean removedByUser = channelsByUserId.remove(userId, nonNullChannel);
        boolean removedByConnection = channelsByConnectionId.remove(nonNullChannel.id().asLongText(), nonNullChannel);
        return removedByUser || removedByConnection;
    }

    @Override
    /**
     * 校验用户、会话版本和路由版本都对得上后，再真正关掉旧连接。
     */
    public boolean kickConnection(long userId, String connectionId, long sessionVersion, long expectedRouteEpoch, String reason) {
        Channel channel = channelsByConnectionId.get(connectionId);
        if (channel == null || !channel.isOpen()) {
            return false;
        }
        Long boundUserId = channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get();
        Long boundSessionVersion = channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get();
        Long boundRouteEpoch = channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get();
        if (boundUserId == null || boundSessionVersion == null || boundRouteEpoch == null) {
            return false;
        }
        if (boundUserId != userId || boundSessionVersion != sessionVersion || boundRouteEpoch != expectedRouteEpoch) {
            return false;
        }
        try {
            channel.close();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    /**
     * 返回本机这条连接当前绑定到什么会话，以及它是不是还拥有最新路由。
     */
    public Optional<LocalConnectionStateSnapshot> findLocalConnectionState(long userId, String connectionId) {
        Channel channel = channelsByConnectionId.get(connectionId);
        if (channel == null || !channel.isOpen()) {
            return Optional.empty();
        }
        Long boundUserId = channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get();
        String sessionId = channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get();
        Long sessionVersion = channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get();
        Long routeEpoch = channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get();
        if (boundUserId == null || sessionId == null || sessionVersion == null || routeEpoch == null || boundUserId != userId) {
            return Optional.empty();
        }
        return Optional.of(new LocalConnectionStateSnapshot(
            sessionId,
            sessionVersion,
            routeEpoch,
            SessionBindingHandler.hasActiveRouteOwnership(channel)
        ));
    }

    @Override
    /**
     * 在 drain 宽限期结束时，触发所有已绑定连接主动退场。
     */
    public int closeBoundConnections() {
        int closedConnections = 0;
        for (Channel channel : new ArrayList<>(channelsByConnectionId.values())) {
            if (channel == null || !channel.isOpen()) {
                continue;
            }
            closedConnections++;
            // 先发事件，让绑定处理器把在线路由和本地状态清干净，再真正关连接。
            channel.pipeline().fireUserEventTriggered(SessionBindingHandler.DRAIN_GRACE_EXPIRED_EVENT);
            if (channel.isOpen()) {
                channel.close();
            }
        }
        return closedConnections;
    }
}
