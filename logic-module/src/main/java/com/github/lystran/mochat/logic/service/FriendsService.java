package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.FriendListRepository;
import com.github.lystran.mochat.logic.repository.FriendshipRepository;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

@Singleton
public final class FriendsService {
    private final FriendListRepository friendListRepository;
    private final FriendshipRepository friendshipRepository;

    public FriendsService(FriendListRepository friendListRepository, FriendshipRepository friendshipRepository) {
        this.friendListRepository = Objects.requireNonNull(friendListRepository, "friendListRepository");
        this.friendshipRepository = Objects.requireNonNull(friendshipRepository, "friendshipRepository");
    }

    public List<FriendSummary> listFriends(long userId) {
        requirePositive(userId, "userId");
        return friendListRepository.listActiveFriends(userId).stream()
            .map(friend -> new FriendSummary(friend.conversationId(), friend.userId(), friend.username()))
            .toList();
    }

    public FriendRequestSummary sendFriendRequest(long fromUserId, long toUserId, String sign) {
        requirePositive(fromUserId, "fromUserId");
        requirePositive(toUserId, "toUserId");
        requireText(sign, "sign");
        return toFriendRequestSummary(friendshipRepository.createFriendRequest(fromUserId, toUserId, sign));
    }

    public List<FriendRequestSummary> listSentFriendRequests(long userId) {
        requirePositive(userId, "userId");
        return friendshipRepository.listSentFriendRequests(userId).stream()
            .map(FriendsService::toFriendRequestSummary)
            .toList();
    }

    public List<FriendRequestSummary> listReceivedFriendRequests(long userId) {
        requirePositive(userId, "userId");
        return friendshipRepository.listReceivedFriendRequests(userId).stream()
            .map(FriendsService::toFriendRequestSummary)
            .toList();
    }

    public FriendRequestSummary handleFriendRequest(long requestId, long handlerUserId, FriendRequestDecision decision) {
        requirePositive(requestId, "requestId");
        requirePositive(handlerUserId, "handlerUserId");
        Objects.requireNonNull(decision, "decision");
        return toFriendRequestSummary(friendshipRepository.handleFriendRequest(
            requestId,
            handlerUserId,
            switch (decision) {
                case ACCEPT -> FriendshipRepository.FriendRequestDecision.ACCEPT;
                case REJECT -> FriendshipRepository.FriendRequestDecision.REJECT;
            }
        ));
    }

    public FriendshipMutationSummary deleteFriend(long requesterUserId, long friendUserId) {
        requirePair(requesterUserId, friendUserId);
        friendshipRepository.deleteFriendship(requesterUserId, friendUserId);
        return new FriendshipMutationSummary(friendUserId, "deleted");
    }

    public FriendshipMutationSummary blockFriend(long requesterUserId, long friendUserId) {
        requirePair(requesterUserId, friendUserId);
        friendshipRepository.blockFriendship(requesterUserId, friendUserId);
        return new FriendshipMutationSummary(friendUserId, "blocked");
    }

    public FriendshipMutationSummary unblockFriend(long requesterUserId, long friendUserId) {
        requirePair(requesterUserId, friendUserId);
        friendshipRepository.unblockFriend(requesterUserId, friendUserId);
        return new FriendshipMutationSummary(friendUserId, "ok");
    }

    private static FriendRequestSummary toFriendRequestSummary(FriendshipRepository.FriendRequestRow row) {
        return new FriendRequestSummary(
            row.requestId(),
            row.fromUserId(),
            row.toUserId(),
            row.sign(),
            row.status(),
            row.createdAtEpochMillis(),
            row.handledAtEpochMillis().orElse(null)
        );
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static void requirePair(long requesterUserId, long friendUserId) {
        requirePositive(requesterUserId, "requesterUserId");
        requirePositive(friendUserId, "friendUserId");
        if (requesterUserId == friendUserId) {
            throw new IllegalArgumentException("friendUserId must differ from requesterUserId");
        }
    }

    public enum FriendRequestDecision {
        ACCEPT,
        REJECT
    }

    public record FriendSummary(long conversationId, long userId, String username) {
    }

    public record FriendRequestSummary(
        long requestId,
        long fromUserId,
        long toUserId,
        String sign,
        String status,
        long createdAtEpochMillis,
        @Nullable Long handledAtEpochMillis
    ) {
    }

    public record FriendshipMutationSummary(long friendUserId, String status) {
    }
}
