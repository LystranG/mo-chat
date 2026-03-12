package com.github.lystran.mochat.common.session;

public interface ChannelSessionRegistry<C> {
    void bind(ResolvedSession resolvedSession, C channelRef);

    boolean unbind(String sessionId, long userId, C channelRef);
}
