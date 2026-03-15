package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.HistoryRepository;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 统一处理消息历史查询入参，并把仓储结果整理成 service 层返回对象。
 */
@Singleton
@Requires(beans = HistoryRepository.class)
public final class HistoryService {
    /** 历史查询没传 limit 时，默认返回 50 条。 */
    public static final int DEFAULT_LIMIT = 50;
    /** 历史查询单次最多返回 50 条，避免一次拉太多。 */
    public static final int MAX_LIMIT = 50;

    private final HistoryRepository historyRepository;

    /**
     * 创建历史消息服务。
     */
    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository");
    }

    /**
     * 按“从某条消息之前继续往前翻”的方式查询历史。
     */
    public List<HistoryMessage> query(long conversationId, Long cursorSeq, int limit) {
        return query(conversationId, cursorSeq, null, null, limit);
    }

    /**
     * 根据游标模式或区间模式查询历史，并统一收口返回条数。
     */
    public List<HistoryMessage> query(long conversationId, Long cursorSeq, Long startSeq, Long endSeq, int limit) {
        int resolvedLimit = normalizeLimit(limit);
        return historyRepository.findHistory(conversationId, cursorSeq, startSeq, endSeq, resolvedLimit)
            .stream()
            .map(message -> new HistoryMessage(message.seq(), message.msgId(), message.serverTimeMs(), message.payloadBase64()))
            .toList();
    }

    /**
     * 把外部传入的 limit 规范成系统允许的窗口大小。
     */
    private int normalizeLimit(int limit) {
        // 两种查询模式统一在 service 层裁剪窗口，避免 controller / repository 各自维护一套 50 条规则。
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 返回给上层的历史消息数据。
     */
    public record HistoryMessage(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
