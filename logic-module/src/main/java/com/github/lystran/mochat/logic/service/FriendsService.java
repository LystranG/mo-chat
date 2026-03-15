package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.FriendListRepository;
import com.github.lystran.mochat.logic.repository.FriendshipRepository;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 封装好友列表、好友申请以及关系变更相关业务。
 */
@Singleton
public final class FriendsService {
    private final FriendListRepository friendListRepository;
    private final FriendshipRepository friendshipRepository;

    /**
     * 使用好友列表仓储和关系仓储构造服务。
     */
    public FriendsService(FriendListRepository friendListRepository, FriendshipRepository friendshipRepository) {
        this.friendListRepository = Objects.requireNonNull(friendListRepository, "friendListRepository");
        this.friendshipRepository = Objects.requireNonNull(friendshipRepository, "friendshipRepository");
    }

    /**
     * 列出用户当前的有效好友关系摘要。
     */
    public List<FriendSummary> listFriends(long userId) {
        requirePositive(userId, "userId");
        return friendListRepository.listActiveFriends(userId).stream()
            .map(friend -> new FriendSummary(friend.conversationId(), friend.userId(), friend.username()))
            .toList();
    }

    /**
     * 提交新的好友申请。
     */
    public FriendRequestSummary sendFriendRequest(long fromUserId, long toUserId, String sign) {
        requirePositive(fromUserId, "fromUserId");
        requirePositive(toUserId, "toUserId");
        if (fromUserId == toUserId) {
            throw new IllegalArgumentException("toUserId must differ from fromUserId");
        }
        requireText(sign, "sign");
        return toFriendRequestSummary(friendshipRepository.createFriendRequest(fromUserId, toUserId, sign));
    }

    /**
     * 列出当前用户发出的好友申请。
     */
    public List<FriendRequestSummary> listSentFriendRequests(long userId) {
        requirePositive(userId, "userId");
        return friendshipRepository.listSentFriendRequests(userId).stream()
            .map(FriendsService::toFriendRequestSummary)
            .toList();
    }

    /**
     * 列出当前用户收到的好友申请。
     */
    public List<FriendRequestSummary> listReceivedFriendRequests(long userId) {
        requirePositive(userId, "userId");
        return friendshipRepository.listReceivedFriendRequests(userId).stream()
            .map(FriendsService::toFriendRequestSummary)
            .toList();
    }

    /**
     * 审批一条好友申请。
     */
    public FriendRequestSummary handleFriendRequest(long requestId, long handlerUserId, FriendRequestDecision decision) {
        requirePositive(requestId, "requestId");
        requirePositive(handlerUserId, "handlerUserId");
        Objects.requireNonNull(decision, "decision");
        return toFriendRequestSummary(friendshipRepository.handleFriendRequest(
            requestId,
            handlerUserId,
            switch (decision) {
                // 服务层枚举与仓储层枚举保持解耦，避免控制器直接依赖底层类型。
                case ACCEPT -> FriendshipRepository.FriendRequestDecision.ACCEPT;
                case REJECT -> FriendshipRepository.FriendRequestDecision.REJECT;
            }
        ));
    }

    /**
     * 删除双方之间的好友关系。
     */
    public FriendshipMutationSummary deleteFriend(long requesterUserId, long friendUserId) {
        requirePair(requesterUserId, friendUserId);
        friendshipRepository.deleteFriendship(requesterUserId, friendUserId);
        return new FriendshipMutationSummary(friendUserId, "deleted");
    }

    /**
     * 将指定好友关系标记为拉黑状态。
     */
    public FriendshipMutationSummary blockFriend(long requesterUserId, long friendUserId) {
        requirePair(requesterUserId, friendUserId);
        friendshipRepository.blockFriendship(requesterUserId, friendUserId);
        return new FriendshipMutationSummary(friendUserId, "blocked");
    }

    /**
     * 解除对指定好友关系的拉黑状态。
     */
    public FriendshipMutationSummary unblockFriend(long requesterUserId, long friendUserId) {
        requirePair(requesterUserId, friendUserId);
        friendshipRepository.unblockFriend(requesterUserId, friendUserId);
        return new FriendshipMutationSummary(friendUserId, "ok");
    }

    /**
     * 将仓储层好友申请记录转换为服务层摘要。
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
     * 校验数值参数必须为正数。
     */
    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    /**
     * 校验文本参数必须提供有效内容。
     */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    /**
     * 校验好友关系变更涉及的两个用户 ID。
     */
    private static void requirePair(long requesterUserId, long friendUserId) {
        requirePositive(requesterUserId, "requesterUserId");
        requirePositive(friendUserId, "friendUserId");
        if (requesterUserId == friendUserId) {
            throw new IllegalArgumentException("friendUserId must differ from requesterUserId");
        }
    }

    /**
     * 表示好友申请的审批结果。
     */
    public enum FriendRequestDecision {
        ACCEPT,
        REJECT
    }

    /**
     * 表示好友列表中的单个好友摘要。
     */
    public record FriendSummary(long conversationId, long userId, String username) {
    }

    /**
     * 表示好友申请的服务层视图。
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
     * 表示好友关系变更的结果摘要。
     */
    public record FriendshipMutationSummary(long friendUserId, String status) {
    }
}
