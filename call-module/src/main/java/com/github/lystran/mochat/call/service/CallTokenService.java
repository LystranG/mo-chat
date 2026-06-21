package com.github.lystran.mochat.call.service;

import com.github.lystran.mochat.call.dto.CallRoomName;
import com.github.lystran.mochat.call.dto.CallRoomState;
import com.github.lystran.mochat.call.dto.CallRoomType;
import com.github.lystran.mochat.call.manager.CallRoomManager;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 签发 LiveKit 入房 token。 */
@Singleton
public final class CallTokenService {
    private final CallRoomManager callRoomManager;
    private final CallRelationshipService relationshipService;
    private final String livekitUrl;
    private final String apiKey;
    private final String apiSecret;

    public CallTokenService(
        CallRoomManager callRoomManager,
        CallRelationshipService relationshipService,
        @Property(name = "mochat.livekit.url", defaultValue = "") String livekitUrl,
        @Property(name = "mochat.livekit.api-key", defaultValue = "") String apiKey,
        @Property(name = "mochat.livekit.api-secret", defaultValue = "") String apiSecret
    ) {
        this.callRoomManager = Objects.requireNonNull(callRoomManager, "callRoomManager");
        this.relationshipService = Objects.requireNonNull(relationshipService, "relationshipService");
        this.livekitUrl = normalize(livekitUrl);
        this.apiKey = normalize(apiKey);
        this.apiSecret = normalize(apiSecret);
    }

    public String livekitUrl() {
        ensureConfigured();
        return livekitUrl;
    }

    public String issueToken(long userId, String roomName) {
        ensureConfigured();
        CallRoomName parsed = CallRoomName.parse(roomName);
        CallRoomState room = callRoomManager.findRoom(parsed.value())
            .orElseThrow(() -> new IllegalArgumentException("call room is not active"));
        if (!room.callId().equals(parsed.callId())) {
            throw new IllegalArgumentException("callId does not match active room");
        }

        if (parsed.type() == CallRoomType.PRIVATE) {
            if (!parsed.allowsPrivateUser(userId)) {
                throw new IllegalArgumentException("user is not a participant of this private call");
            }
            requireActivePrivateRelationship(userId, parsed.privatePeerOf(userId));
        } else if (!relationshipService.isActiveGroupMember(parsed.groupId(), userId)) {
            throw new IllegalArgumentException("user is not an active group member");
        }

        AccessToken token = new AccessToken(apiKey, apiSecret);
        String identity = Long.toString(userId);
        token.setName(identity);
        token.setIdentity(identity);
        token.addGrants(
            new RoomJoin(true),
            new RoomName(parsed.value()),
            new CanPublish(true),
            new CanSubscribe(true)
        );
        return token.toJwt();
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

    private void ensureConfigured() {
        if (livekitUrl.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw new IllegalStateException("mochat.livekit.url, api-key and api-secret must be configured");
        }
    }

    private static String normalize(String value) {

        return value == null ? "" : value.trim();
    }
}
