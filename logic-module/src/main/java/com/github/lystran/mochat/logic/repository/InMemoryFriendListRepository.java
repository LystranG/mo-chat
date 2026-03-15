package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * 在没有数据源时返回空好友列表的内存兜底实现。
 */
@Singleton
@Requires(missingBeans = FriendListRepository.class)
public final class InMemoryFriendListRepository implements FriendListRepository {
    @Override
    // 返回空好友列表，表示当前运行时不提供持久化好友数据。
    public List<FriendRow> listActiveFriends(long userId) {
        return List.of();
    }
}
