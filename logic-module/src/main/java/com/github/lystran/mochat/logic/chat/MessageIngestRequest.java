package com.github.lystran.mochat.logic.chat;

import java.util.Objects;

/**
 * 描述消息摄入主链路所需的统一入参，屏蔽私聊和群聊在上游协议上的差异。
 */
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

    /**
     * 校验消息种类和编码后的消息内容等必填字段。
     */
    public MessageIngestRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payloadBase64, "payloadBase64");
    }

    /**
     * 构造私聊消息的摄入请求。
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
     * 构造群聊消息的摄入请求。
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
