package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import com.github.lystran.mochat.protocol.ErrorCode;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

@Singleton
@Requires(missingBeans = MessageSendPolicyGateway.class)
@Requires(property = "micronaut.application.name", notEquals = "message-service", defaultValue = "")
public final class RepositoryBackedMessageSendPolicyGateway implements MessageSendPolicyGateway {
    private final MessageRelationshipRepository messageRelationshipRepository;
    private final ReceiptConversationStateStore receiptConversationStateStore;

    public RepositoryBackedMessageSendPolicyGateway(
        MessageRelationshipRepository messageRelationshipRepository,
        ReceiptConversationStateStore receiptConversationStateStore
    ) {
        this.messageRelationshipRepository = Objects.requireNonNull(messageRelationshipRepository, "messageRelationshipRepository");
        this.receiptConversationStateStore = Objects.requireNonNull(receiptConversationStateStore, "receiptConversationStateStore");
    }

    @Override
    public void validatePrivateMessage(long conversationId, long senderUid, long recipientUid) {
        if (conversationId <= 0L || senderUid <= 0L || recipientUid <= 0L || senderUid == recipientUid) {
            throw new MessageRejectException(ErrorCode.NOT_FRIEND, "private message requires active friendship");
        }

        long peerUidLow = Math.min(senderUid, recipientUid);
        long peerUidHigh = Math.max(senderUid, recipientUid);
        ReceiptConversationStateStore.PrivateConversationState conversationState = receiptConversationStateStore.findPrivateConversation(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("private conversation not found: " + conversationId));
        if (conversationState.uidLow() != peerUidLow || conversationState.uidHigh() != peerUidHigh) {
            throw new IllegalArgumentException("private conversation participants mismatch");
        }

        MessageRelationshipRepository.PrivateMessageState state = messageRelationshipRepository.privateMessageState(
            conversationId,
            peerUidLow,
            peerUidHigh
        );
        if (state == MessageRelationshipRepository.PrivateMessageState.NOT_FRIEND) {
            throw new MessageRejectException(ErrorCode.NOT_FRIEND, "private message requires active friendship");
        }
        if (state == MessageRelationshipRepository.PrivateMessageState.BLOCKED) {
            throw new MessageRejectException(ErrorCode.FRIEND_BLOCKED, "friendship is blocked");
        }
    }

    @Override
    public List<Long> resolveGroupRecipientUids(long groupId, long senderUid) {
        if (groupId <= 0L || !messageRelationshipRepository.groupExists(groupId)) {
            throw new MessageRejectException(ErrorCode.NOT_IN_GROUP, "group not found");
        }
        if (senderUid <= 0L || !messageRelationshipRepository.isActiveGroupMember(groupId, senderUid)) {
            throw new MessageRejectException(ErrorCode.NOT_IN_GROUP, "sender is not an active group member");
        }
        return List.copyOf(messageRelationshipRepository.listActiveGroupMemberIds(groupId));
    }
}
