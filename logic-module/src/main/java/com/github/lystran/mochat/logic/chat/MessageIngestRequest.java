package com.github.lystran.mochat.logic.chat;

import java.util.Objects;

/**
 * 表示 message-service 收到的一次发消息请求。
 */
public record MessageIngestRequest(
    long senderUid,
    long conversationId,
    long clientMsgId,
    String kind,
    Long peerUidLow,
    Long peerUidHigh,
    Long groupId,
    // 这里放的是原始消息正文，已经去掉 sessionId 并做过 Base64 编码。
    String payloadBase64
) {
    public static final String KIND_PRIVATE = "PRIVATE";
    public static final String KIND_GROUP = "GROUP";

    /**
     * 校验请求里最基本的必填字段。
     */
    public MessageIngestRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payloadBase64, "payloadBase64");
    }

    /**
     * 创建一条从连接层转来的私聊请求。
     */
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

    /**
     * 创建一条从连接层转来的群聊请求。
     */
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
