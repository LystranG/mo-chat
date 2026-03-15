package com.github.lystran.mochat.common.session;

/**
 * 用来记住会话、用户和在线连接之间怎么对应。
 */
public interface ChannelSessionRegistry<C> {
    /**
     * 记下某个会话、用户和连接是一组。
     */
    void bind(String sessionId, long userId, C channelRef);

    /**
     * 只有这组关系对得上时，才把它解绑。
     */
    boolean unbind(String sessionId, long userId, C channelRef);
}
