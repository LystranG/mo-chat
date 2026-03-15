package com.github.lystran.mochat.logic.chat;

/**
 * 表示一条准备发给单个接收人的私聊消息。
 */
public record PrivateMessageDelivery(
    long conversationId,
    long msgId,
    long seq,
    long serverTimeMs,
    long senderUid,
    long recipientUid,
    // 这里放的是消息正文，已经做过 Base64 编码，方便后面在线投递或离线补发。
    String payloadBase64
) {
}
