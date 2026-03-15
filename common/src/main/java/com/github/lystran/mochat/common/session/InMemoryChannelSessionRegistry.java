package com.github.lystran.mochat.common.session;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用内存结构记住 sessionId 对应哪个用户、哪条连接。
 */
public final class InMemoryChannelSessionRegistry<C> implements ChannelSessionRegistry<C> {
    private final UserChannelDirectory<C> userChannelDirectory;
    private final ConcurrentMap<String, SessionBinding<C>> bindingsBySessionId = new ConcurrentHashMap<>();

    /**
     * 收下用户连接目录，方便同时维护“用户对应哪条连接”这张表。
     */
    public InMemoryChannelSessionRegistry(UserChannelDirectory<C> userChannelDirectory) {
        this.userChannelDirectory = Objects.requireNonNull(userChannelDirectory, "userChannelDirectory");
    }

    /**
     * 把 sessionId 绑定到新的用户连接上；必要时先把旧用户那边的连接记录清掉。
     */
    @Override
    public void bind(String sessionId, long userId, C channelRef) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(channelRef, "channelRef");

        bindingsBySessionId.compute(sessionId, (ignored, current) -> {
            if (current != null && current.userId() != userId) {
                // 同一个 session 如果重新绑到了别的用户上，要先把旧用户那边的连接记录删掉。
                userChannelDirectory.unbind(current.userId(), current.channelRef());
            }
            userChannelDirectory.bind(userId, channelRef);
            return new SessionBinding<>(userId, channelRef);
        });
    }

    /**
     * 同时解绑 session 这边和用户那边的连接记录。
     */
    @Override
    public boolean unbind(String sessionId, long userId, C channelRef) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(channelRef, "channelRef");

        boolean removed = bindingsBySessionId.remove(sessionId, new SessionBinding<>(userId, channelRef));
        boolean directoryUnbound = userChannelDirectory.unbind(userId, channelRef);
        return removed || directoryUnbound;
    }

    /**
     * 保存某个 session 当前绑着的用户和连接。
     */
    private record SessionBinding<C>(long userId, C channelRef) {
    }
}
