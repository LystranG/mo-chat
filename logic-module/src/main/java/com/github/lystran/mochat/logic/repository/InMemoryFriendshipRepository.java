package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;

@Singleton
@Requires(missingBeans = FriendshipRepository.class)
public final class InMemoryFriendshipRepository implements FriendshipRepository {
    @Override
    public FriendRequestRow createFriendRequest(long fromUserId, long toUserId, String sign) {
        throw new UnsupportedOperationException("friend request persistence requires datasource-backed repository");
    }

    @Override
    public List<FriendRequestRow> listSentFriendRequests(long userId) {
        return List.of();
    }

    @Override
    public List<FriendRequestRow> listReceivedFriendRequests(long userId) {
        return List.of();
    }

    @Override
    public FriendRequestRow handleFriendRequest(long requestId, long handlerUserId, FriendRequestDecision decision) {
        throw new UnsupportedOperationException("friend request persistence requires datasource-backed repository");
    }

    @Override
    public void deleteFriendship(long userId, long friendUserId) {
        throw new UnsupportedOperationException("friendship mutation requires datasource-backed repository");
    }

    @Override
    public void blockFriendship(long userId, long friendUserId) {
        throw new UnsupportedOperationException("friendship mutation requires datasource-backed repository");
    }

    @Override
    public void unblockFriend(long userId, long friendUserId) {
        throw new UnsupportedOperationException("friendship mutation requires datasource-backed repository");
    }
}
