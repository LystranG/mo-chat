package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 发送消息前先检查关系限制，避免把不该发送的私聊或群聊继续往后处理。
 */
@Singleton
public final class MessageSendPolicyService {
    private final MessageRelationshipRepository messageRelationshipRepository;

    /**
     * 创建消息发送校验服务。
     */
    public MessageSendPolicyService(MessageRelationshipRepository messageRelationshipRepository) {
        this.messageRelationshipRepository = Objects.requireNonNull(messageRelationshipRepository, "messageRelationshipRepository");
    }

    /**
     * 检查一条私聊消息当前能不能发送。
     */
    public PrivateMessagingPolicy checkPrivateMessagingPolicy(long conversationId, long senderUid, long recipientUid) {
        if (conversationId <= 0 || senderUid <= 0 || recipientUid <= 0 || senderUid == recipientUid) {
            return PrivateMessagingPolicy.NOT_FRIEND;
        }

        // 私聊关系表固定按 uid_1 < uid_2 存，所以查询前先把两边用户排好顺序。
        MessageRelationshipRepository.PrivateMessageState state = messageRelationshipRepository.privateMessageState(
            conversationId,
            Math.min(senderUid, recipientUid),
            Math.max(senderUid, recipientUid)
        );
        return switch (state) {
            case ACTIVE -> PrivateMessagingPolicy.ALLOWED;
            case NOT_FRIEND -> PrivateMessagingPolicy.NOT_FRIEND;
            case BLOCKED -> PrivateMessagingPolicy.BLOCKED;
        };
    }

    /**
     * 检查群消息能不能发，并在允许时顺手带回当前活跃成员列表。
     */
    public GroupSendContext getGroupSendContext(long groupId, long senderUid) {
        if (groupId <= 0) {
            return new GroupSendContext(GroupSendEligibility.GROUP_NOT_FOUND, List.of());
        }
        if (senderUid <= 0) {
            return new GroupSendContext(GroupSendEligibility.NOT_MEMBER, List.of());
        }
        // 先确认群还存在，再确认发消息的人是不是当前活跃成员。
        if (!messageRelationshipRepository.groupExists(groupId)) {
            return new GroupSendContext(GroupSendEligibility.GROUP_NOT_FOUND, List.of());
        }
        if (!messageRelationshipRepository.isActiveGroupMember(groupId, senderUid)) {
            return new GroupSendContext(GroupSendEligibility.NOT_MEMBER, List.of());
        }
        return new GroupSendContext(
            GroupSendEligibility.ALLOWED,
            List.copyOf(messageRelationshipRepository.listActiveGroupMemberIds(groupId))
        );
    }

    /**
     * 私聊发送校验结果。
     */
    public enum PrivateMessagingPolicy {
        ALLOWED,
        NOT_FRIEND,
        BLOCKED
    }

    /**
     * 群消息发送校验结果。
     */
    public enum GroupSendEligibility {
        ALLOWED,
        NOT_MEMBER,
        GROUP_NOT_FOUND
    }

    /**
     * 群消息发送时需要返回给上层的上下文。
     */
    public record GroupSendContext(GroupSendEligibility eligibility, List<Long> memberUids) {
    }
}
