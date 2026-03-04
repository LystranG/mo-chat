package com.github.lystran.mochat.logic.chat;

import java.util.Objects;

public record MessageIngestRequest(
    long senderUid,
    long conversationId,
    long clientMsgId,
    String kind,
    Long peerUidLow,
    Long peerUidHigh,
    Long groupId,
    String payloadBase64
) {
    public static final String KIND_PRIVATE = "PRIVATE";
    public static final String KIND_GROUP = "GROUP";

    public MessageIngestRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payloadBase64, "payloadBase64");
    }

    public static MessageIngestRequest privateMessage(
        long senderUid,
        long conversationId,
        long clientMsgId,
        long peerUidLow,
        long peerUidHigh,
        String payloadBase64
    ) {
        return new MessageIngestRequest(
            senderUid,
            conversationId,
            clientMsgId,
            KIND_PRIVATE,
            peerUidLow,
            peerUidHigh,
            null,
            payloadBase64
        );
    }

    public static MessageIngestRequest groupMessage(
        long senderUid,
        long conversationId,
        long clientMsgId,
        long groupId,
        String payloadBase64
    ) {
        return new MessageIngestRequest(
            senderUid,
            conversationId,
            clientMsgId,
            KIND_GROUP,
            null,
            null,
            groupId,
            payloadBase64
        );
    }
}
