package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

@Singleton
public final class MessageSendPolicyService {
    private final MessageRelationshipRepository messageRelationshipRepository;

    public MessageSendPolicyService(MessageRelationshipRepository messageRelationshipRepository) {
        this.messageRelationshipRepository = Objects.requireNonNull(messageRelationshipRepository, "messageRelationshipRepository");
    }

    public PrivateMessagingPolicy checkPrivateMessagingPolicy(long conversationId, long senderUid, long recipientUid) {
        if (conversationId <= 0 || senderUid <= 0 || recipientUid <= 0 || senderUid == recipientUid) {
            return PrivateMessagingPolicy.NOT_FRIEND;
        }

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

    public GroupSendContext getGroupSendContext(long groupId, long senderUid) {
        if (groupId <= 0) {
            return new GroupSendContext(GroupSendEligibility.GROUP_NOT_FOUND, List.of());
        }
        if (senderUid <= 0) {
            return new GroupSendContext(GroupSendEligibility.NOT_MEMBER, List.of());
        }
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

    public enum PrivateMessagingPolicy {
        ALLOWED,
        NOT_FRIEND,
        BLOCKED
    }

    public enum GroupSendEligibility {
        ALLOWED,
        NOT_MEMBER,
        GROUP_NOT_FOUND
    }

    public record GroupSendContext(GroupSendEligibility eligibility, List<Long> memberUids) {
    }
}
