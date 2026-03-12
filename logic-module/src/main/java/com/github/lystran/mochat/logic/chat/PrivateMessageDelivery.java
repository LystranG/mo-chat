package com.github.lystran.mochat.logic.chat;

public record PrivateMessageDelivery(
    long conversationId,
    long msgId,
    long seq,
    long serverTimeMs,
    long senderUid,
    long recipientUid,
    String payloadBase64
) {
}
