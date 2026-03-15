package com.github.lystran.mochat.common.directory;

import java.util.Optional;

/**
 * 用来记住“哪个用户现在连着哪条连接”。
 */
public interface UserChannelDirectory<C> {
    /**
     * 记下某个用户当前对应的连接。
     */
    void bind(long userId, C channelRef);

    /**
     * 查某个用户现在有没有连接。
     */
    Optional<C> find(long userId);

    /**
     * 只有连接对象对得上时，才删掉这条用户到连接的记录。
     */
    boolean unbind(long userId, C channelRef);
}
