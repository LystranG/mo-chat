package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.HistoryRepository;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 统一历史消息查询入口，并负责把每次最多返回多少条控制在固定范围内。
 */
@Singleton
@Requires(beans = HistoryRepository.class)
public final class HistoryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 50;

    private final HistoryRepository historyRepository;

    /**
     * 使用历史仓储构造历史查询服务。
     */
    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository");
    }

    /**
     * 按“从某条 seq 往前翻”的方式查询历史消息。
     */
    public List<HistoryMessage> query(long conversationId, Long cursorSeq, int limit) {
        return query(conversationId, cursorSeq, null, null, limit);
    }

    /**
     * 按“从某条 seq 往前翻”或“给定起止 seq”两种方式查询历史消息。
     */
    public List<HistoryMessage> query(long conversationId, Long cursorSeq, Long startSeq, Long endSeq, int limit) {
        int resolvedLimit = normalizeLimit(limit);
        return historyRepository.findHistory(conversationId, cursorSeq, startSeq, endSeq, resolvedLimit)
            .stream()
            .map(message -> new HistoryMessage(message.seq(), message.msgId(), message.serverTimeMs(), message.payloadBase64()))
            .toList();
    }

    /**
     * 将调用方给出的 limit 规范化到允许的固定窗口内。
     */
    private int normalizeLimit(int limit) {
        // 两种查询模式统一在 service 层裁剪窗口，避免 controller / repository 各自维护一套 50 条规则。
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 表示历史查询返回的单条消息摘要。
     */
    public record HistoryMessage(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
