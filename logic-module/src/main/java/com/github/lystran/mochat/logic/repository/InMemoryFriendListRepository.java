package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * 无数据库时使用的好友列表空实现。
 */
@Singleton
@Requires(missingBeans = FriendListRepository.class)
public final class InMemoryFriendListRepository implements FriendListRepository {
    /**
     * 内存兜底模式下返回空好友列表。
     */
    @Override
    public List<FriendRow> listActiveFriends(long userId) {
        return List.of();
    }
}
