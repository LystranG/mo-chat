package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.FriendListRepository;
import com.github.lystran.mochat.logic.repository.FriendshipRepository;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 处理好友列表、好友申请和拉黑等社交关系操作。
 */
@Singleton
public final class FriendsService {
    private final FriendListRepository friendListRepository;
    private final FriendshipRepository friendshipRepository;

    /**
     * 创建好友服务。
     */
    public FriendsService(FriendListRepository friendListRepository, FriendshipRepository friendshipRepository) {
        this.friendListRepository = Objects.requireNonNull(friendListRepository, "friendListRepository");
        this.friendshipRepository = Objects.requireNonNull(friendshipRepository, "friendshipRepository");
    }

    /**
     * 查询用户当前的好友列表。
     */
    public List<FriendSummary> listFriends(long userId) {
        requirePositive(userId, "userId");
        return friendListRepository.listActiveFriends(userId).stream()
            .map(friend -> new FriendSummary(friend.conversationId(), friend.userId(), friend.username()))
            .toList();
    }

    /**
     * 发起一条好友申请。
     */
    public FriendRequestSummary sendFriendRequest(long fromUserId, long toUserId, String sign) {
        requirePositive(fromUserId, "fromUserId");
        requirePositive(toUserId, "toUserId");
        if (fromUserId == toUserId) {
            throw new IllegalArgumentException("toUserId must differ from fromUserId");
        }
        // sign 由客户端生成并原样透传存库，这里只做是否为空的基本校验。
        requireText(sign, "sign");
        return toFriendRequestSummary(friendshipRepository.createFriendRequest(fromUserId, toUserId, sign));
    }

    /**
     * 查询自己发出去的好友申请。
     */
    public List<FriendRequestSummary> listSentFriendRequests(long userId) {
        requirePositive(userId, "userId");
        return friendshipRepository.listSentFriendRequests(userId).stream()
            .map(FriendsService::toFriendRequestSummary)
            .toList();
    }

    /**
     * 查询发给自己的好友申请。
     */
    public List<FriendRequestSummary> listReceivedFriendRequests(long userId) {
        requirePositive(userId, "userId");
        return friendshipRepository.listReceivedFriendRequests(userId).stream()
            .map(FriendsService::toFriendRequestSummary)
            .toList();
    }

    /**
     * 处理一条好友申请，通过或拒绝都会在这里收口。
     */
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

    /**
     * 删除一条好友关系。
     */
    public FriendshipMutationSummary deleteFriend(long requesterUserId, long friendUserId) {
        requirePair(requesterUserId, friendUserId);
        friendshipRepository.deleteFriendship(requesterUserId, friendUserId);
        return new FriendshipMutationSummary(friendUserId, "deleted");
    }

    /**
     * 把某个好友拉黑。
     */
    public FriendshipMutationSummary blockFriend(long requesterUserId, long friendUserId) {
        requirePair(requesterUserId, friendUserId);
        friendshipRepository.blockFriendship(requesterUserId, friendUserId);
        return new FriendshipMutationSummary(friendUserId, "blocked");
    }

    /**
     * 解除自己发起的拉黑状态。
     */
    public FriendshipMutationSummary unblockFriend(long requesterUserId, long friendUserId) {
        requirePair(requesterUserId, friendUserId);
        friendshipRepository.unblockFriend(requesterUserId, friendUserId);
        return new FriendshipMutationSummary(friendUserId, "ok");
    }

    /**
     * 把仓储层的好友申请记录整理成 service 返回结构。
     */
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

    /**
     * 要求某个 id 必须是正数。
     */
    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    /**
     * 要求字符串不能为空白。
     */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    /**
     * 校验好友关系两端的用户 id 是否有效且不是同一个人。
     */
    private static void requirePair(long requesterUserId, long friendUserId) {
        requirePositive(requesterUserId, "requesterUserId");
        requirePositive(friendUserId, "friendUserId");
        if (requesterUserId == friendUserId) {
            throw new IllegalArgumentException("friendUserId must differ from requesterUserId");
        }
    }

    /**
     * 好友申请处理动作。
     */
    public enum FriendRequestDecision {
        ACCEPT,
        REJECT
    }

    /**
     * 好友列表里的单条好友摘要。
     */
    public record FriendSummary(long conversationId, long userId, String username) {
    }

    /**
     * 返回给上层的好友申请摘要。
     */
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

    /**
     * 好友关系操作完成后的简单结果。
     */
    public record FriendshipMutationSummary(long friendUserId, String status) {
    }
}
