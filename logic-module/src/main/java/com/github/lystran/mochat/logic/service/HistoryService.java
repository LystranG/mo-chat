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

    private final HistoryRepository historyRepository;

    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository");
    }

    public List<HistoryMessage> query(long conversationId, Long cursorSeq, int limit) {
        int resolvedLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        return historyRepository.findHistory(conversationId, cursorSeq, resolvedLimit)
            .stream()
            .map(message -> new HistoryMessage(message.seq(), message.msgId(), message.serverTimeMs(), message.payloadBase64()))
            .toList();
    }

    public record HistoryMessage(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
