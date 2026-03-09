package com.github.lystran.mochat.message.contract;

public record PersistenceAckEvent(
    long msgId,
    long conversationId,
    long seq,
    long serverTimeMs
) {
}
