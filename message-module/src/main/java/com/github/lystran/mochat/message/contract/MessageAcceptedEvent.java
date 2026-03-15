package com.github.lystran.mochat.message.contract;

import java.util.Objects;

/**
 * 表示逻辑层已经收下并排好顺序的一条消息，后面会沿着 MQ 和落库流程继续往下传。
 */
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

    // 检查这条消息带的字段是不是成套的，避免把私聊和群聊需要的信息混着往下传。
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

    // 组装一条私聊消息，顺手把私聊双方的 uid 范围一起带上。
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

    // 组装一条群消息，群消息只认 groupId，不再占用私聊对端那几个字段。
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

    // 返回会话维度的分组键，让同一会话的消息始终落到同一条顺序处理链路上。
    public String shardingKey() {
        return Long.toString(conversationId);
    }
}
