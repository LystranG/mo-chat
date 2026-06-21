package com.github.lystran.mochat.call.manager;

import com.github.lystran.mochat.call.dto.CallRoomState;
import com.github.lystran.mochat.call.dto.CallRoomName;
import com.github.lystran.mochat.call.dto.CallRoomType;

import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 管理当前进程内的活跃音视频通话房间。 */
@Singleton
public final class CallRoomManager {
    private final Map<String, MutableCallRoomState> activeRooms = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public CallRoomState createPrivateRoom(long fromUserId, long toUserId, String callId) {
        String roomName = CallRoomName.privateRoomName(fromUserId, toUserId, callId);
        MutableCallRoomState state = new MutableCallRoomState(
            roomName,
            callId,
            CallRoomType.PRIVATE,
            null,
            fromUserId,
            clock.instant()
        );
        activeRooms.put(roomName, state);
        return state.snapshot();
    }

    public CallRoomState createGroupRoom(long groupId, long startedByUserId, String callId) {
        String roomName = CallRoomName.groupRoomName(groupId, callId);
        MutableCallRoomState state = new MutableCallRoomState(
            roomName,
            callId,
            CallRoomType.GROUP,
            groupId,
            startedByUserId,
            clock.instant()
        );
        state.participants.add(startedByUserId);
        activeRooms.put(roomName, state);
        return state.snapshot();
    }

    public void markJoined(String roomName, long userId) {
        requireRoom(roomName).participants.add(userId);
    }

    public Optional<CallRoomState> leave(String roomName, long userId) {
        MutableCallRoomState state = activeRooms.get(CallRoomName.parse(roomName).value());
        if (state == null) {
            return Optional.empty();
        }
        state.participants.remove(userId);
        if (state.participants.isEmpty()) {
            activeRooms.remove(state.roomName, state);
            return Optional.empty();
        }
        return Optional.of(state.snapshot());
    }

    public void endRoom(String roomName) {
        activeRooms.remove(CallRoomName.parse(roomName).value());
    }

    public Optional<CallRoomState> findRoom(String roomName) {
        MutableCallRoomState state = activeRooms.get(CallRoomName.parse(roomName).value());
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    public boolean isActive(String roomName, String callId) {
        return findRoom(roomName)
            .filter(room -> room.callId().equals(callId))
            .isPresent();
    }

    private MutableCallRoomState requireRoom(String roomName) {
        MutableCallRoomState state = activeRooms.get(CallRoomName.parse(roomName).value());
        if (state == null) {
            throw new IllegalArgumentException("call room is not active");
        }
        return state;
    }

    private static final class MutableCallRoomState {
        private final String roomName;
        private final String callId;
        private final CallRoomType type;
        private final Long groupId;
        private final long startedByUserId;
        private final Instant startedAt;
        private final Set<Long> participants = ConcurrentHashMap.newKeySet();

        private MutableCallRoomState(
            String roomName,
            String callId,
            CallRoomType type,
            Long groupId,
            long startedByUserId,
            Instant startedAt
        ) {
            this.roomName = roomName;
            this.callId = callId;
            this.type = type;
            this.groupId = groupId;
            this.startedByUserId = startedByUserId;
            this.startedAt = startedAt;
        }

        private CallRoomState snapshot() {
            return new CallRoomState(
                roomName,
                callId,
                type,
                groupId,
                startedByUserId,
                startedAt,
                Set.copyOf(participants)
            );
        }
    }
}
