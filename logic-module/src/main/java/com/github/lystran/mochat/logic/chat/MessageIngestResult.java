package com.github.lystran.mochat.logic.chat;

/**
 * 表示消息被接受后返回给调用方的结果。
 */
public record MessageIngestResult(
    long clientMsgId,
    long msgId,
    long seq,
    long serverTimeMs
) {
}
