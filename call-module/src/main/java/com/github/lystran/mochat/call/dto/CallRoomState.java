package com.github.lystran.mochat.call.dto;

import java.time.Instant;
import java.util.Set;

/** 活跃通话房间快照。 */
public record CallRoomState(
    String roomName,
    String callId,
    CallRoomType type,
    Long groupId,
    long startedByUserId,
    Instant startedAt,
    Set<Long> participants
) {
}
