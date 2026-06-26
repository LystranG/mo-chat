package com.github.lystran.mochat.logic.repository;

import java.util.List;

/**
 * 历史消息查询仓储。
 */
public interface HistoryRepository {
    /**
     * 按游标模式查询历史消息。
     */
    List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, int limit);

    /**
     * 按游标或闭区间模式查询历史消息。
     */
    List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, Long startSeq, Long endSeq, int limit);

    /**
     * 单条历史消息记录。
     */
    record HistoryMessage(long seq, long msgId, long senderUid, long serverTimeMs, String payloadBase64) {
    }
}
