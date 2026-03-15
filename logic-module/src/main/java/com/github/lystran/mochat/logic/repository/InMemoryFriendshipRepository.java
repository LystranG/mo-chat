package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * 在没有数据源时拒绝写操作、对读操作返回空结果的兜底好友仓储。
 */
@Singleton
@Requires(missingBeans = FriendshipRepository.class)
public final class InMemoryFriendshipRepository implements FriendshipRepository {
    @Override
    // 明确告知当前运行时不支持落库好友申请。
    public FriendRequestRow createFriendRequest(long fromUserId, long toUserId, String sign) {
        throw new UnsupportedOperationException("friend request persistence requires datasource-backed repository");
    }

    @Override
    // 无持久化时返回空的“已发送申请”列表。
    public List<FriendRequestRow> listSentFriendRequests(long userId) {
        return List.of();
    }

    @Override
    // 无持久化时返回空的“已接收申请”列表。
    public List<FriendRequestRow> listReceivedFriendRequests(long userId) {
        return List.of();
    }

    @Override
    // 明确告知当前运行时不支持处理好友申请。
    public FriendRequestRow handleFriendRequest(long requestId, long handlerUserId, FriendRequestDecision decision) {
        throw new UnsupportedOperationException("friend request persistence requires datasource-backed repository");
    }

    @Override
    // 明确告知当前运行时不支持删除好友关系。
    public void deleteFriendship(long userId, long friendUserId) {
        throw new UnsupportedOperationException("friendship mutation requires datasource-backed repository");
    }

    @Override
    // 明确告知当前运行时不支持拉黑好友。
    public void blockFriendship(long userId, long friendUserId) {
        throw new UnsupportedOperationException("friendship mutation requires datasource-backed repository");
    }

    @Override
    // 明确告知当前运行时不支持解除拉黑。
    public void unblockFriend(long userId, long friendUserId) {
        throw new UnsupportedOperationException("friendship mutation requires datasource-backed repository");
    }
}
