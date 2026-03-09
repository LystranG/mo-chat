package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;

@Singleton
@Requires(missingBeans = FriendListRepository.class)
public final class InMemoryFriendListRepository implements FriendListRepository {
    @Override
    public List<FriendRow> listActiveFriends(long userId) {
        return List.of();
    }
}
