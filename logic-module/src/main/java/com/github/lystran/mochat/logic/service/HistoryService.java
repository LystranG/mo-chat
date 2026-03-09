package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.HistoryRepository;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

@Singleton
@Requires(beans = HistoryRepository.class)
public final class HistoryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 50;

    private final HistoryRepository historyRepository;

    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository");
    }

    public List<HistoryMessage> query(long conversationId, Long cursorSeq, int limit) {
        return query(conversationId, cursorSeq, null, null, limit);
    }

    public List<HistoryMessage> query(long conversationId, Long cursorSeq, Long startSeq, Long endSeq, int limit) {
        int resolvedLimit = normalizeLimit(limit);
        return historyRepository.findHistory(conversationId, cursorSeq, startSeq, endSeq, resolvedLimit)
            .stream()
            .map(message -> new HistoryMessage(message.seq(), message.msgId(), message.serverTimeMs(), message.payloadBase64()))
            .toList();
    }

    private int normalizeLimit(int limit) {
        // 两种查询模式统一在 service 层裁剪窗口，避免 controller / repository 各自维护一套 50 条规则。
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    public record HistoryMessage(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
