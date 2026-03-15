package com.github.lystran.mochat.message.contract;

/**
 * 表示持久化服务完成落库后回传的结果。
 */
public record PersistenceAckEvent(
    long msgId,
    long conversationId,
    long seq,
    long serverTimeMs
) {
}
