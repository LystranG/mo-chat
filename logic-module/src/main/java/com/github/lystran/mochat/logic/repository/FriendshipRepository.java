package com.github.lystran.mochat.logic.repository;

import java.util.List;
import java.util.Optional;

/**
 * 定义好友申请与好友关系变更接口。
 */
public interface FriendshipRepository {
    // 创建新的好友申请。
    FriendRequestRow createFriendRequest(long fromUserId, long toUserId, String sign);

    // 查询用户已发出的好友申请。
    List<FriendRequestRow> listSentFriendRequests(long userId);

    // 查询用户收到的好友申请。
    List<FriendRequestRow> listReceivedFriendRequests(long userId);

    // 处理指定好友申请。
    FriendRequestRow handleFriendRequest(long requestId, long handlerUserId, FriendRequestDecision decision);

    // 删除一条好友关系。
    void deleteFriendship(long userId, long friendUserId);

    // 将一条好友关系标记为拉黑。
    void blockFriendship(long userId, long friendUserId);

    // 解除一条被拉黑的好友关系。
    void unblockFriend(long userId, long friendUserId);

    /** 好友申请处理动作。 */
    enum FriendRequestDecision {
        ACCEPT,
        REJECT
    }

    /** 好友申请的一条读取结果。 */
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
