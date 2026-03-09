package com.github.lystran.mochat.common.session;

public interface ChannelSessionRegistry<C> {
    void bind(String sessionId, long userId, C channelRef);

    boolean unbind(String sessionId, long userId, C channelRef);
}
