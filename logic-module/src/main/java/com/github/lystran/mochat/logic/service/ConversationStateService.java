package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.ConversationStateRepository;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.Optional;

/**
 * 为 HTTP 层提供会话访问控制和最新状态查询能力。
 */
@Singleton
@Requires(beans = ConversationStateRepository.class)
public final class ConversationStateService {
    private final ConversationStateRepository conversationStateRepository;

    /**
     * 使用底层会话状态仓储构造服务。
     */
    public ConversationStateService(ConversationStateRepository conversationStateRepository) {
        this.conversationStateRepository = Objects.requireNonNull(conversationStateRepository, "conversationStateRepository");
    }

    /**
     * 判断请求方是否有权访问指定会话。
     */
    public boolean hasConversationAccess(long conversationId, long requesterUid) {
        return conversationStateRepository.hasConversationAccess(conversationId, requesterUid);
    }

    /**
     * 查询私聊对端当前已接收的最新消息序号。
     */
    public Optional<Long> findPrivatePeerLatestReceivedSeq(long conversationId, long requesterUid) {
        return conversationStateRepository.findPrivatePeerLatestReceivedSeq(conversationId, requesterUid);
    }

    /**
     * 查询会话维度的最新消息状态。
     */
    public Optional<ConversationLatestState> findConversationLatestState(long conversationId) {
        return conversationStateRepository.findConversationLatestState(conversationId)
            .map(state -> new ConversationLatestState(state.conversationId(), state.latestSeq(), state.latestMessageTime()));
    }

    /**
     * 表示对外暴露的会话最新状态摘要。
     */
    public record ConversationLatestState(long conversationId, long latestSeq, long latestMessageTime) {
    }
}
