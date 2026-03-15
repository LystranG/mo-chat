package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * 无数据库时使用的好友关系兜底实现。
 * 只允许读空列表，写操作直接提示当前模式不支持。
 */
@Singleton
@Requires(missingBeans = FriendshipRepository.class)
public final class InMemoryFriendshipRepository implements FriendshipRepository {
    /**
     * 内存兜底模式不支持创建好友申请。
     */
    @Override
    public FriendRequestRow createFriendRequest(long fromUserId, long toUserId, String sign) {
        throw new UnsupportedOperationException("friend request persistence requires datasource-backed repository");
    }

    /**
     * 内存兜底模式下返回空的已发送申请列表。
     */
    @Override
    public List<FriendRequestRow> listSentFriendRequests(long userId) {
        return List.of();
    }

    /**
     * 内存兜底模式下返回空的已接收申请列表。
     */
    @Override
    public List<FriendRequestRow> listReceivedFriendRequests(long userId) {
        return List.of();
    }

    /**
     * 内存兜底模式不支持处理好友申请。
     */
    @Override
    public FriendRequestRow handleFriendRequest(long requestId, long handlerUserId, FriendRequestDecision decision) {
        throw new UnsupportedOperationException("friend request persistence requires datasource-backed repository");
    }

    /**
     * 内存兜底模式不支持删除好友关系。
     */
    @Override
    public void deleteFriendship(long userId, long friendUserId) {
        throw new UnsupportedOperationException("friendship mutation requires datasource-backed repository");
    }

    /**
     * 内存兜底模式不支持拉黑好友。
     */
    @Override
    public void blockFriendship(long userId, long friendUserId) {
        throw new UnsupportedOperationException("friendship mutation requires datasource-backed repository");
    }

    /**
     * 内存兜底模式不支持解除拉黑。
     */
    @Override
    public void unblockFriend(long userId, long friendUserId) {
        throw new UnsupportedOperationException("friendship mutation requires datasource-backed repository");
    }
}
