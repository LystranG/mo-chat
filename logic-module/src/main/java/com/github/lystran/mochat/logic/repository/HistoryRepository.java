package com.github.lystran.mochat.logic.repository;

import java.util.List;

public interface HistoryRepository {
    List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, int limit);

    record HistoryMessage(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
