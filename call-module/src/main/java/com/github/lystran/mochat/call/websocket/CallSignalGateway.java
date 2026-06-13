package com.github.lystran.mochat.call.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lystran.mochat.call.dto.CallSignalMessage;

import io.micronaut.http.MediaType;
import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 音视频通话信令网关：管理在线 WebSocket 会话并投递信令。 */
@Singleton
public final class CallSignalGateway {
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<Long, WebSocketSession> sessionsByUserId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> userIdsBySessionId = new ConcurrentHashMap<>();

    public CallSignalGateway(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void register(long userId, WebSocketSession session) {
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        Objects.requireNonNull(session, "session");
        WebSocketSession previous = sessionsByUserId.put(userId, session);
        if (previous != null && previous != session && previous.isOpen()) {
            previous.close();
        }
        userIdsBySessionId.put(session.getId(), userId);
    }

    public void unregister(WebSocketSession session) {
        if (session == null) {
            return;
        }
        Long userId = userIdsBySessionId.remove(session.getId());
        if (userId != null) {
            sessionsByUserId.remove(userId, session);
        }
    }

    public Optional<Long> userId(WebSocketSession session) {
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(userIdsBySessionId.get(session.getId()));
    }

    public boolean isOnline(long userId) {
        return findSession(userId).isPresent();
    }

    public boolean sendToUser(long userId, CallSignalMessage message) {
        return sendJsonToUser(userId, toJson(message));
    }

    public boolean sendJsonToUser(long userId, String payloadJson) {
        Objects.requireNonNull(payloadJson, "payloadJson");
        return findSession(userId)
            .map(session -> {
                try {
                    session.sendSync(payloadJson, MediaType.APPLICATION_JSON_TYPE);
                    return true;
                } catch (RuntimeException exception) {
                    unregister(session);
                    return false;
                }
            })
            .orElse(false);
    }

    private Optional<WebSocketSession> findSession(long userId) {
        WebSocketSession session = sessionsByUserId.get(userId);
        if (session == null || !session.isOpen() || !session.isWritable()) {
            if (session != null) {
                unregister(session);
            }
            return Optional.empty();
        }
        return Optional.of(session);
    }

    private String toJson(CallSignalMessage message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "type", message.type(),
                "callId", message.callId(),
                "fromUserId", message.fromUserId(),
                "toUserId", message.toUserId(),
                "groupId", message.groupId(),
                "roomName", message.roomName(),
                "timestampMillis", message.timestampMillis()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize signal", e);
        }
    }
}
