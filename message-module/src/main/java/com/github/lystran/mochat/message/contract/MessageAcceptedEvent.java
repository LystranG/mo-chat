package com.github.lystran.mochat.message.contract;

import java.util.Objects;

public record MessageAcceptedEvent(
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
    private static final String PRIVATE_KIND = "private";
    private static final String GROUP_KIND = "group";

    public MessageAcceptedEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payloadBase64, "payloadBase64");
        if (PRIVATE_KIND.equals(kind) && (peerUidLow == null || peerUidHigh == null)) {
            throw new IllegalArgumentException("private message requires peerUidLow/peerUidHigh");
        }
        if (GROUP_KIND.equals(kind) && groupId == null) {
            throw new IllegalArgumentException("group message requires groupId");
        }
    }

    public static MessageAcceptedEvent privateMessage(
        long msgId,
        long conversationId,
        long seq,
        long clientMsgId,
        long senderUid,
        Long peerUidLow,
        Long peerUidHigh,
        long serverTimeMs,
        String payloadBase64
    ) {
        return new MessageAcceptedEvent(
            msgId,
            conversationId,
            seq,
            clientMsgId,
            PRIVATE_KIND,
            senderUid,
            peerUidLow,
            peerUidHigh,
            null,
            serverTimeMs,
            payloadBase64
        );
    }

    public static MessageAcceptedEvent groupMessage(
        long msgId,
        long conversationId,
        long seq,
        long clientMsgId,
        long senderUid,
        Long groupId,
        long serverTimeMs,
        String payloadBase64
    ) {
        return new MessageAcceptedEvent(
            msgId,
            conversationId,
            seq,
            clientMsgId,
            GROUP_KIND,
            senderUid,
            null,
            null,
            groupId,
            serverTimeMs,
            payloadBase64
        );
    }

    public String shardingKey() {
        return Long.toString(conversationId);
    }
}
