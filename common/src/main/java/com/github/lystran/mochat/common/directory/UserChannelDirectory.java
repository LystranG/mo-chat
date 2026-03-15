package com.github.lystran.mochat.common.directory;

import java.util.Optional;

/**
 * 按用户 id 记录和查找当前在线连接。
 */
public interface UserChannelDirectory<C> {
    /**
     * 记住某个用户现在对应哪条连接。
     */
    void bind(long userId, C channelRef);

    /**
     * 查某个用户当前有没有在线连接。
     */
    Optional<C> find(long userId);

    /**
     * 只在连接对象对得上的时候，清掉这个用户的在线记录。
     */
    boolean unbind(long userId, C channelRef);
}
