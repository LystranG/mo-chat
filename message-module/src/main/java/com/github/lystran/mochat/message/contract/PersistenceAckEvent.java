package com.github.lystran.mochat.message.contract;

/**
 * 表示消息已经真正写进数据库后的回执，方便上游确认这条消息已经落稳了。
 */
public record PersistenceAckEvent(
    long msgId,
    long conversationId,
    long seq,
    long serverTimeMs
) {
}
