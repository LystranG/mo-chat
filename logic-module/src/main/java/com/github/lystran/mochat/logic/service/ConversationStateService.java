package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.ConversationStateRepository;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.Optional;

/**
 * 统一读取会话可见范围和最新进度，给 HTTP 读侧提供更直接的返回结构。
 */
@Singleton
@Requires(beans = ConversationStateRepository.class)
public final class ConversationStateService {
    private final ConversationStateRepository conversationStateRepository;

    /**
     * 创建会话状态服务。
     */
    public ConversationStateService(ConversationStateRepository conversationStateRepository) {
        this.conversationStateRepository = Objects.requireNonNull(conversationStateRepository, "conversationStateRepository");
    }

    /**
     * 判断当前用户能不能查看这个会话。
     */
    public boolean hasConversationAccess(long conversationId, long requesterUid) {
        return conversationStateRepository.hasConversationAccess(conversationId, requesterUid);
    }

    /**
     * 查询私聊对方已经确认收到哪条消息。
     */
    public Optional<Long> findPrivatePeerLatestReceivedSeq(long conversationId, long requesterUid) {
        return conversationStateRepository.findPrivatePeerLatestReceivedSeq(conversationId, requesterUid);
    }

    /**
     * 查询会话目前推进到哪条消息、最后一条消息时间是多少。
     */
    public Optional<ConversationLatestState> findConversationLatestState(long conversationId) {
        return conversationStateRepository.findConversationLatestState(conversationId)
            .map(state -> new ConversationLatestState(state.conversationId(), state.latestSeq(), state.latestMessageTime()));
    }

    /**
     * 返回给上层的会话最新状态。
     */
    public record ConversationLatestState(long conversationId, long latestSeq, long latestMessageTime) {
    }
}
