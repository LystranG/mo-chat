package com.github.lystran.mochat.logic.chat;

/**
 * 表示消息摄入成功后返回给上游的最终编号和时间戳结果。
 */
public record MessageIngestResult(
    long clientMsgId,
    long msgId,
    long seq,
    long serverTimeMs
) {
}
