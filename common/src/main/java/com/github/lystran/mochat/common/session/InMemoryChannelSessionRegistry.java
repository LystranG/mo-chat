package com.github.lystran.mochat.common.session;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryChannelSessionRegistry<C> implements ChannelSessionRegistry<C> {
    private final UserChannelDirectory<C> userChannelDirectory;
    private final ConcurrentMap<String, SessionBinding<C>> bindingsBySessionId = new ConcurrentHashMap<>();

    public InMemoryChannelSessionRegistry(UserChannelDirectory<C> userChannelDirectory) {
        this.userChannelDirectory = Objects.requireNonNull(userChannelDirectory, "userChannelDirectory");
    }

    @Override
    public void bind(ResolvedSession resolvedSession, C channelRef) {
        Objects.requireNonNull(resolvedSession, "resolvedSession");
        Objects.requireNonNull(channelRef, "channelRef");
        String sessionId = resolvedSession.sessionId();
        long userId = resolvedSession.userId();

        bindingsBySessionId.compute(sessionId, (ignored, current) -> {
            if (current != null && current.userId() != userId) {
                userChannelDirectory.unbind(current.userId(), current.channelRef());
            }
            userChannelDirectory.bind(userId, channelRef);
            return new SessionBinding<>(userId, channelRef);
        });
    }

    @Override
    public boolean unbind(String sessionId, long userId, C channelRef) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(channelRef, "channelRef");

        boolean removed = bindingsBySessionId.remove(sessionId, new SessionBinding<>(userId, channelRef));
        boolean directoryUnbound = userChannelDirectory.unbind(userId, channelRef);
        return removed || directoryUnbound;
    }

    private record SessionBinding<C>(long userId, C channelRef) {
    }
}
