package com.github.lystran.mochat.call.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lystran.mochat.call.service.CallService;
import com.github.lystran.mochat.call.service.CallSessionResolver;
import com.github.lystran.mochat.call.service.CallOfflineNotificationService;

import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** 音视频通话信令 WebSocket。 */
@ServerWebSocket("/calls/ws/{sessionId}")
public final class CallWebSocket {
    private final CallSessionResolver sessionResolver;
    private final CallSignalGateway signalGateway;
    private final CallOfflineNotificationService offlineNotificationService;
    private final CallService callService;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    public CallWebSocket(
        CallSessionResolver sessionResolver,
        CallSignalGateway signalGateway,
        CallOfflineNotificationService offlineNotificationService,
        CallService callService,
        ObjectMapper objectMapper
    ) {
        this.sessionResolver = Objects.requireNonNull(sessionResolver, "sessionResolver");
        this.signalGateway = Objects.requireNonNull(signalGateway, "signalGateway");
        this.offlineNotificationService = Objects.requireNonNull(offlineNotificationService, "offlineNotificationService");
        this.callService = Objects.requireNonNull(callService, "callService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @OnOpen
    public void onOpen(String sessionId, WebSocketSession session) {
        var userId = sessionResolver.resolveUserId(sessionId);
        if (userId.isEmpty()) {
            session.close(CloseReason.POLICY_VIOLATION);
            return;
        }
        signalGateway.register(userId.get(), session);
        sendJson(session, Map.of(
            "type", "call_signal_ready",
            "toUserId", userId.get(),
            "timestampMillis", clock.millis()
        ));
        offlineNotificationService.pushPendingNotifications(userId.get());
    }

    @OnMessage
    public void onMessage(String json, WebSocketSession session) {
        Long fromUserId = signalGateway.userId(session).orElse(null);
        if (fromUserId == null) {
            sendError(session, "websocket is not bound to a user");
            return;
        }
        try {
            PeerSignalRequest request = objectMapper.readValue(json, PeerSignalRequest.class);
            if (!fromUserId.equals(request.fromUserId())) {
                sendError(session, "fromUserId must match websocket userId");
                return;
            }
            var result = callService.forwardPrivateSignal(
                request.fromUserId(),
                request.toUserId(),
                request.type(),
                request.roomName()
            );
            if ("call_accept".equals(request.type()) && result.token() != null) {
                sendJson(session, Map.of(
                    "type", "call_accepted_with_token",
                    "token", result.token(),
                    "livekitUrl", result.livekitUrl(),
                    "roomName", request.roomName()
                ));
            }
            if (!result.delivered()) {
                sendJson(session, Map.of(
                    "type", "call_peer_offline",
                    "toUserId", request.toUserId()
                ));
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendError(session, exception.getMessage());
        } catch (JsonProcessingException exception) {
            sendError(session, "invalid json: " + exception.getOriginalMessage());
        }
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        signalGateway.unregister(session);
    }

    private void sendJson(WebSocketSession session, Object payload) {
        try {
            session.sendSync(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // ignore serialization error
        }
    }

    private void sendError(WebSocketSession session, String message) {
        sendJson(session, Map.of("type", "error", "message", message));
    }

    public record PeerSignalRequest(Long fromUserId, Long toUserId, String type, String roomName) {
    }
}
