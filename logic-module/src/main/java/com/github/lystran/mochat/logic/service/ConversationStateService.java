package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.ConversationStateRepository;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.Optional;

@Singleton
@Requires(beans = ConversationStateRepository.class)
public final class ConversationStateService {
    private final ConversationStateRepository conversationStateRepository;

    public ConversationStateService(ConversationStateRepository conversationStateRepository) {
        this.conversationStateRepository = Objects.requireNonNull(conversationStateRepository, "conversationStateRepository");
    }

    public boolean hasConversationAccess(long conversationId, long requesterUid) {
        return conversationStateRepository.hasConversationAccess(conversationId, requesterUid);
    }

    public Optional<Long> findPrivatePeerLatestReceivedSeq(long conversationId, long requesterUid) {
        return conversationStateRepository.findPrivatePeerLatestReceivedSeq(conversationId, requesterUid);
    }

    public Optional<ConversationLatestState> findConversationLatestState(long conversationId) {
        return conversationStateRepository.findConversationLatestState(conversationId)
            .map(state -> new ConversationLatestState(state.conversationId(), state.latestSeq(), state.latestMessageTime()));
    }

    public record ConversationLatestState(long conversationId, long latestSeq, long latestMessageTime) {
    }
}
