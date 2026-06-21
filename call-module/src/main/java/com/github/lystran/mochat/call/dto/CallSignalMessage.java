package com.github.lystran.mochat.call.dto;

/** 发给在线客户端的通话信令。 */
public record CallSignalMessage(
    String type,
    String callId,
    long fromUserId,
    long toUserId,
    long groupId,
    String roomName,
    long timestampMillis
) {
}
