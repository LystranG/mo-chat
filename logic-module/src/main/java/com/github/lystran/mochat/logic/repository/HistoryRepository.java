package com.github.lystran.mochat.logic.repository;

import java.util.List;

/**
 * 定义会话历史读取接口。
 */
public interface HistoryRepository {
    // 从某条消息之前继续往前查一页历史消息。
    List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, int limit);

    // 要么从某条消息之前继续查，要么按 startSeq 到 endSeq 这一段去查。
    List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, Long startSeq, Long endSeq, int limit);

    /** 历史消息的一条读取结果。 */
    record HistoryMessage(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
