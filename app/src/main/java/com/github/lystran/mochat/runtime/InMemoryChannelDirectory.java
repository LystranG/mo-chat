package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用内存记录“用户当前连着哪个通道”。
 */
public final class InMemoryChannelDirectory<C> implements UserChannelDirectory<C> {
    private final ConcurrentMap<Long, C> channelsByUserId = new ConcurrentHashMap<>(); // key 是用户 id，value 是当前在线通道。

    /**
     * 绑定用户和当前通道。
     */
    @Override
    public void bind(long userId, C channelRef) {
        channelsByUserId.put(userId, Objects.requireNonNull(channelRef, "channelRef"));
    }

    /**
     * 查询用户现在对应的通道。
     */
    @Override
    public Optional<C> find(long userId) {
        return Optional.ofNullable(channelsByUserId.get(userId));
    }

    /**
     * 只有通道对象仍然一致时，才解除这次绑定。
     */
    @Override
    public boolean unbind(long userId, C channelRef) {
        return channelsByUserId.remove(userId, Objects.requireNonNull(channelRef, "channelRef"));
    }
}
