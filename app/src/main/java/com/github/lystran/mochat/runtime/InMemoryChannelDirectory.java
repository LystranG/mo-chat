package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryChannelDirectory<C> implements UserChannelDirectory<C> {
    private final ConcurrentMap<Long, C> channelsByUserId = new ConcurrentHashMap<>();

    @Override
    public void bind(long userId, C channelRef) {
        channelsByUserId.put(userId, Objects.requireNonNull(channelRef, "channelRef"));
    }

    @Override
    public Optional<C> find(long userId) {
        return Optional.ofNullable(channelsByUserId.get(userId));
    }

    @Override
    public boolean unbind(long userId, C channelRef) {
        return channelsByUserId.remove(userId, Objects.requireNonNull(channelRef, "channelRef"));
    }
}
