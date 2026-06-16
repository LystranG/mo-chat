package com.github.lystran.mochat.call.service;

import com.github.lystran.mochat.call.dto.CallSignalMessage;
import com.github.lystran.mochat.call.dto.CallRoomState;
import com.github.lystran.mochat.call.dto.CallRoomName;
import com.github.lystran.mochat.call.dto.CallRoomType;
import com.github.lystran.mochat.call.entity.CallOfflineNotification;
import com.github.lystran.mochat.call.manager.CallRoomManager;
import com.github.lystran.mochat.call.websocket.CallSignalGateway;

import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 音视频通话主业务服务。 */
@Singleton
public final class CallService {
    private static final Set<String> PEER_SIGNAL_TYPES = Set.of(
        "call_accept",
        "call_reject",
        "call_cancel",
        "call_hangup"
    );

    private final CallRelationshipService relationshipService;
    private final CallRoomManager callRoomManager;
    private final CallTokenService callTokenService;
    private final CallSignalGateway signalGateway;
    private final CallOfflineNotificationService offlineNotificationService;
    private final Clock clock = Clock.systemUTC();

    public CallService(
        CallRelationshipService relationshipService,
        CallRoomManager callRoomManager,
        CallTokenService callTokenService,
        CallSignalGateway signalGateway,
        CallOfflineNotificationService offlineNotificationService
    ) {
        this.relationshipService = Objects.requireNonNull(relationshipService, "relationshipService");
        this.callRoomManager = Objects.requireNonNull(callRoomManager, "callRoomManager");
        this.callTokenService = Objects.requireNonNull(callTokenService, "callTokenService");
        this.signalGateway = Objects.requireNonNull(signalGateway, "signalGateway");
        this.offlineNotificationService = Objects.requireNonNull(offlineNotificationService, "offlineNotificationService");
    }

    public PrivateCallInviteResult invitePrivateCall(long fromUserId, long toUserId) {
        requireDifferentPositiveUsers(fromUserId, toUserId);
        requireActivePrivateRelationship(fromUserId, toUserId);

        String callId = newCallId();
        CallRoomState room = callRoomManager.createPrivateRoom(fromUserId, toUserId, callId);

        boolean callInvite = signalGateway.sendToUser(toUserId, new CallSignalMessage(
                "call_invite",
                callId,
                fromUserId,
                toUserId,
                0L,
                room.roomName(),
                clock.millis()
        ));
        if(!callInvite){
           //插入离线消息
            offlineNotificationService.enqueueBatch(List.of(new CallSignalMessage(
                    "call_invite",
                    callId,
                    fromUserId,
                    toUserId,
                    -1,
                    room.roomName(),
                    clock.millis()
            )));
            callRoomManager.endRoom(room.roomName());
        }
        String token = callInvite ? callTokenService.issueToken(fromUserId, room.roomName()):null;
        return new PrivateCallInviteResult(callId, room.roomName(), fromUserId, toUserId, token, callTokenService.livekitUrl());
    }

    public PrivateSignalResult forwardPrivateSignal(long fromUserId, long toUserId, String type, String roomName) {
        requireDifferentPositiveUsers(fromUserId, toUserId);
        String normalizedType = normalizeSignalType(type);
        if (!PEER_SIGNAL_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("type must be call_accept, call_reject, call_cancel, or call_hangup");
        }

        CallRoomName parsed = CallRoomName.parse(roomName);
        if (parsed.type() != CallRoomType.PRIVATE) {
            throw new IllegalArgumentException("roomName must be a private call room");
        }
        if (!parsed.allowsPrivateUser(fromUserId) || !parsed.allowsPrivateUser(toUserId)) {
            throw new IllegalArgumentException("signal users must be participants of the private call room");
        }
        requireActivePrivateRelationship(fromUserId, toUserId);
        requireActiveRoom(parsed);

        if ("call_accept".equals(normalizedType)) {
            callRoomManager.markJoined(parsed.value(), fromUserId);
            callRoomManager.markJoined(parsed.value(), toUserId);
        }
        if ("call_reject".equals(normalizedType) || "call_cancel".equals(normalizedType) || "call_hangup".equals(normalizedType)) {
            callRoomManager.endRoom(parsed.value());
        }

        boolean delivered = signalGateway.sendToUser(toUserId, new CallSignalMessage(
            normalizedType,
            parsed.callId(),
            fromUserId,
            toUserId,
            0L,
            parsed.value(),
            clock.millis()
        ));

        String token = "call_accept".equals(normalizedType) ? callTokenService.issueToken(fromUserId, parsed.value()) : null;
        String livekitUrl = token == null ? null : callTokenService.livekitUrl();
        return new PrivateSignalResult(delivered, token, livekitUrl);
    }

    public GroupCallStartResult startGroupCall(long fromUserId, long groupId) {
        CallRoomName.requirePositive(fromUserId, "fromUserId");
        CallRoomName.requirePositive(groupId, "groupId");
        List<Long> members = relationshipService.listActiveGroupMemberIds(groupId);
        if (!members.contains(fromUserId)) {
            throw new IllegalArgumentException("caller must be an active group member");
        }

        String callId = newCallId();
        CallRoomState room = callRoomManager.createGroupRoom(groupId, fromUserId, callId);
        String token = callTokenService.issueToken(fromUserId, room.roomName());
        List<Long> notifiedUserIds = new ArrayList<>();
        List<Long> queuedUserIds = new ArrayList<>();
        List<CallSignalMessage> offlineMessages = new ArrayList<>();
        for (Long memberUserId : members) {
            if (memberUserId == null || memberUserId <= 0 || memberUserId == fromUserId) {
                continue;
            }
            CallSignalMessage message = new CallSignalMessage(
                "call_group_started",
                callId,
                fromUserId,
                memberUserId,
                groupId,
                room.roomName(),
                clock.millis()
            );
            if (signalGateway.sendToUser(memberUserId, message)) {
                notifiedUserIds.add(memberUserId);
            } else {
                offlineMessages.add(message);
                queuedUserIds.add(memberUserId);
            }
        }
        if (!offlineMessages.isEmpty()) {
            offlineNotificationService.enqueueBatch(offlineMessages);
        }

        return new GroupCallStartResult(
            callId,
            room.roomName(),
            groupId,
            fromUserId,
            token,
            callTokenService.livekitUrl(),
            List.copyOf(notifiedUserIds),
            List.copyOf(queuedUserIds)
        );
    }

    public CallTokenResult joinGroupCall(long userId, String roomName) {
        CallRoomName parsed = requireGroupRoom(roomName);
        requireActiveGroupMember(parsed.groupId(), userId);
        requireActiveRoom(parsed);
        callRoomManager.markJoined(parsed.value(), userId);
        notifyGroupParticipants(parsed, userId, "call_group_member_joined");
        return new CallTokenResult(callTokenService.issueToken(userId, parsed.value()), callTokenService.livekitUrl());
    }

    public void leaveGroupCall(long userId, String roomName) {
        CallRoomName parsed = requireGroupRoom(roomName);
        requireActiveGroupMember(parsed.groupId(), userId);
        CallRoomState room = requireActiveRoom(parsed);
        if (!room.participants().contains(userId)) {
            throw new IllegalArgumentException("user is not currently in this call room");
        }
        callRoomManager.leave(parsed.value(), userId)
            .ifPresent(remainingRoom -> notifyGroupParticipants(parsed, userId, "call_group_member_left"));
    }

    public String livekitUrl() {
        return callTokenService.livekitUrl();
    }

    public List<Long> pushPendingNotifications(long userId) {
        return offlineNotificationService.pushPendingNotifications(userId);
    }

    private void notifyGroupParticipants(CallRoomName roomName, long actorUserId, String type) {
        callRoomManager.findRoom(roomName.value()).ifPresent(room -> {
            for (Long recipientUserId : room.participants()) {
                if (recipientUserId == null || recipientUserId == actorUserId) {
                    continue;
                }
                signalGateway.sendToUser(recipientUserId, new CallSignalMessage(
                    type,
                    roomName.callId(),
                    actorUserId,
                    recipientUserId,
                    roomName.groupId(),
                    roomName.value(),
                    clock.millis()
                ));
            }
        });
    }

    private CallRoomState requireActiveRoom(CallRoomName roomName) {
        return callRoomManager.findRoom(roomName.value())
            .filter(room -> room.callId().equals(roomName.callId()))
            .orElseThrow(() -> new IllegalArgumentException("call room is not active"));
    }

    private CallRoomName requireGroupRoom(String roomName) {
        CallRoomName parsed = CallRoomName.parse(roomName);
        if (parsed.type() != CallRoomType.GROUP) {
            throw new IllegalArgumentException("roomName must be a group call room");
        }
        return parsed;
    }

    private void requireActivePrivateRelationship(long firstUserId, long secondUserId) {
        var state = relationshipService.privateRelationshipState(firstUserId, secondUserId);
        if (state == CallRelationshipService.PrivateRelationshipState.NOT_FRIEND) {
            throw new IllegalArgumentException("private call requires active friendship");
        }
        if (state == CallRelationshipService.PrivateRelationshipState.BLOCKED) {
            throw new IllegalArgumentException("friendship is blocked");
        }
    }

    private void requireActiveGroupMember(long groupId, long userId) {
        if (!relationshipService.isActiveGroupMember(groupId, userId)) {
            throw new IllegalArgumentException("user is not an active group member");
        }
    }

    private static void requireDifferentPositiveUsers(long fromUserId, long toUserId) {
        CallRoomName.requirePositive(fromUserId, "fromUserId");
        CallRoomName.requirePositive(toUserId, "toUserId");
        if (fromUserId == toUserId) {
            throw new IllegalArgumentException("toUserId must differ from fromUserId");
        }
    }

    private static String normalizeSignalType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static String newCallId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record PrivateCallInviteResult(
        String callId,
        String roomName,
        long fromUserId,
        long toUserId,
        String token,
        String livekitUrl
    ) {
    }

    public record PrivateSignalResult(boolean delivered, String token, String livekitUrl) {
    }

    public record GroupCallStartResult(
        String callId,
        String roomName,
        long groupId,
        long fromUserId,
        String token,
        String livekitUrl,
        List<Long> notifiedUserIds,
        List<Long> queuedUserIds
    ) {
    }

    public record CallTokenResult(String token, String livekitUrl) {
    }
}
