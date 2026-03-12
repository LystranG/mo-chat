package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class InMemoryUserChannelDirectory implements UserChannelDirectory<Channel>, LocalGatewayConnectionDirectory {
    private final ConcurrentMap<Long, Channel> channelsByUserId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Channel> channelsByConnectionId = new ConcurrentHashMap<>();

    @Override
    public void bind(long userId, Channel channelRef) {
        Channel nonNullChannel = Objects.requireNonNull(channelRef, "channelRef");
        channelsByUserId.put(userId, nonNullChannel);
        channelsByConnectionId.put(nonNullChannel.id().asLongText(), nonNullChannel);
    }

    @Override
    public Optional<Channel> find(long userId) {
        return Optional.ofNullable(channelsByUserId.get(userId));
    }

    @Override
    public boolean unbind(long userId, Channel channelRef) {
        Channel nonNullChannel = Objects.requireNonNull(channelRef, "channelRef");
        boolean removedByUser = channelsByUserId.remove(userId, nonNullChannel);
        boolean removedByConnection = channelsByConnectionId.remove(nonNullChannel.id().asLongText(), nonNullChannel);
        return removedByUser || removedByConnection;
    }

    @Override
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
    public int closeBoundConnections() {
        int closedConnections = 0;
        for (Channel channel : new ArrayList<>(channelsByConnectionId.values())) {
            if (channel == null || !channel.isOpen()) {
                continue;
            }
            closedConnections++;
            channel.pipeline().fireUserEventTriggered(SessionBindingHandler.DRAIN_GRACE_EXPIRED_EVENT);
            if (channel.isOpen()) {
                channel.close();
            }
        }
        return closedConnections;
    }
}
