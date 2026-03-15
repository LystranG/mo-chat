package com.github.lystran.mochat.common.session;

/**
 * 记录某个 session 现在绑在哪条连接上。
 */
public interface ChannelSessionRegistry<C> {
    /**
     * 把 session 和连接绑在一起。
     */
    void bind(ResolvedSession resolvedSession, C channelRef);

    /**
     * 只在 session、用户和连接都对得上的时候取消绑定。
     */
    boolean unbind(String sessionId, long userId, C channelRef);
}
