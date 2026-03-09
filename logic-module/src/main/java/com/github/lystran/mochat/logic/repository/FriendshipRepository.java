package com.github.lystran.mochat.logic.repository;

import java.util.List;
import java.util.Optional;

public interface FriendshipRepository {
    FriendRequestRow createFriendRequest(long fromUserId, long toUserId, String sign);

    List<FriendRequestRow> listSentFriendRequests(long userId);

    List<FriendRequestRow> listReceivedFriendRequests(long userId);

    FriendRequestRow handleFriendRequest(long requestId, long handlerUserId, FriendRequestDecision decision);

    void deleteFriendship(long userId, long friendUserId);

    void blockFriendship(long userId, long friendUserId);

    void unblockFriend(long userId, long friendUserId);

    enum FriendRequestDecision {
        ACCEPT,
        REJECT
    }

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
