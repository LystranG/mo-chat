package com.github.lystran.mochat.common.session;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 把 session 和连接关系保存在当前进程内存里。
 */
public final class InMemoryChannelSessionRegistry<C> implements ChannelSessionRegistry<C> {
    private final UserChannelDirectory<C> userChannelDirectory;
    private final ConcurrentMap<String, SessionBinding<C>> bindingsBySessionId = new ConcurrentHashMap<>();

    /**
     * 创建内存版 session 绑定表。
     */
    public InMemoryChannelSessionRegistry(UserChannelDirectory<C> userChannelDirectory) {
        this.userChannelDirectory = Objects.requireNonNull(userChannelDirectory, "userChannelDirectory");
    }

    /**
     * 记住某个 session 当前绑定到哪条连接，同时更新按用户查连接的目录。
     */
    @Override
    public void bind(ResolvedSession resolvedSession, C channelRef) {
        Objects.requireNonNull(resolvedSession, "resolvedSession");
        Objects.requireNonNull(channelRef, "channelRef");
        String sessionId = resolvedSession.sessionId();
        long userId = resolvedSession.userId();

        bindingsBySessionId.compute(sessionId, (ignored, current) -> {
            if (current != null && current.userId() != userId) {
                // 同一个 session 改绑到别的用户时，先把旧用户目录里的记录清掉。
                userChannelDirectory.unbind(current.userId(), current.channelRef());
            }
            userChannelDirectory.bind(userId, channelRef);
            return new SessionBinding<>(userId, channelRef);
        });
    }

    /**
     * 同时尝试清理 session 绑定表和用户目录里的记录。
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
     * 保存一条 session 绑定记录。
     */
    private record SessionBinding<C>(long userId, C channelRef) {
    }
}
