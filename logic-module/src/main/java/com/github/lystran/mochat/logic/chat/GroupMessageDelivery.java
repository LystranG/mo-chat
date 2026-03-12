package com.github.lystran.mochat.logic.chat;

import java.util.List;

public record GroupMessageDelivery(
    long conversationId,
    long msgId,
    long seq,
    long serverTimeMs,
    long senderUid,
    long groupId,
    String payloadBase64,
    List<Long> recipientUids
) {
}
