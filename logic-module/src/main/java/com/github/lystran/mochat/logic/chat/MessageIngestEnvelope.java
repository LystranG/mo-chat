package com.github.lystran.mochat.logic.chat;

import java.util.Objects;

public record MessageIngestEnvelope(
    long msgId,
    long conversationId,
    long seq,
    long clientMsgId,
    String kind,
    long senderUid,
    Long peerUidLow,
    Long peerUidHigh,
    Long groupId,
    long serverTimeMs,
    String payloadBase64
) {
    public MessageIngestEnvelope {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payloadBase64, "payloadBase64");
    }
}
