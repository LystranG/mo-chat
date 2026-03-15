package com.github.lystran.mochat.logic.repository;

import java.util.List;
import java.util.Optional;

/**
 * 好友申请和好友关系写侧仓储。
 */
public interface FriendshipRepository {
    /**
     * 创建一条好友申请。
     */
    FriendRequestRow createFriendRequest(long fromUserId, long toUserId, String sign);

    /**
     * 列出自己发出去的好友申请。
     */
    List<FriendRequestRow> listSentFriendRequests(long userId);

    /**
     * 列出发给自己的好友申请。
     */
    List<FriendRequestRow> listReceivedFriendRequests(long userId);

    /**
     * 处理一条好友申请。
     */
    FriendRequestRow handleFriendRequest(long requestId, long handlerUserId, FriendRequestDecision decision);

    /**
     * 删除一条好友关系。
     */
    void deleteFriendship(long userId, long friendUserId);

    /**
     * 把一条好友关系改成拉黑状态。
     */
    void blockFriendship(long userId, long friendUserId);

    /**
     * 解除自己发起的拉黑状态。
     */
    void unblockFriend(long userId, long friendUserId);

    /**
     * 好友申请处理动作。
     */
    enum FriendRequestDecision {
        ACCEPT,
        REJECT
    }

    /**
     * 好友申请记录。
     */
    record FriendRequestRow(
        long requestId,
        long fromUserId,
        long toUserId,
        String sign,
        String status,
        long createdAtEpochMillis,
        Optional<Long> handledAtEpochMillis
    ) {
    }
}
