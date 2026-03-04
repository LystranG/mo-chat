package com.github.lystran.mochat.common.directory;

import java.util.Optional;

public interface UserChannelDirectory<C> {
    void bind(long userId, C channelRef);

    Optional<C> find(long userId);

    boolean unbind(long userId, C channelRef);
}
