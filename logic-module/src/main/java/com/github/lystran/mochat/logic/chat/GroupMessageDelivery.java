package com.github.lystran.mochat.logic.chat;

import java.util.List;

/**
 * 表示一条准备发给群成员的群消息。
 */
public record GroupMessageDelivery(
    long conversationId,
    long msgId,
    long seq,
    long serverTimeMs,
    long senderUid,
    long groupId,
    // 这里放的是消息正文，已经做过 Base64 编码，方便在服务之间直接传。
    String payloadBase64,
    List<Long> recipientUids
) {
}
