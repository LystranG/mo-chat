package com.github.lystran.mochat.logic.chat;

public record MessageIngestResult(
    long clientMsgId,
    long msgId,
    long seq,
    long serverTimeMs
) {
}
