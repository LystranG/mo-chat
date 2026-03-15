package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用内存 Map 记住“哪个用户当前连着哪条连接”。
 */
public final class InMemoryChannelDirectory<C> implements UserChannelDirectory<C> {
    private final ConcurrentMap<Long, C> channelsByUserId = new ConcurrentHashMap<>();

    /**
     * 把用户和当前连接记到内存里。
     */
    @Override
    public void bind(long userId, C channelRef) {
        channelsByUserId.put(userId, Objects.requireNonNull(channelRef, "channelRef"));
    }

    /**
     * 查这个用户现在有没有在线连接。
     */
    @Override
    public Optional<C> find(long userId) {
        return Optional.ofNullable(channelsByUserId.get(userId));
    }

    /**
     * 只有连接对象对得上时，才把这条用户到连接的记录删掉。
     */
    @Override
    public boolean unbind(long userId, C channelRef) {
        return channelsByUserId.remove(userId, Objects.requireNonNull(channelRef, "channelRef"));
    }
}
