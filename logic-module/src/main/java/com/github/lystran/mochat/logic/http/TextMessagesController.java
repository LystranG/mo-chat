package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.logic.service.ConversationStateService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Desktop-friendly text messaging endpoints used by the local Electron client while
 * the full access-gateway/message-service realtime path is not available locally.
 */
@Singleton
@Controller("/messages")
@Requires(beans = DataSource.class)
public final class TextMessagesController {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private static final String LIST_MESSAGES_SQL = """
        SELECT seq, msg_id, conversation_id, sender_uid, server_ts_ms, payload_base64, message_type
        FROM messages
        WHERE conversation_id = ?
          AND seq > ?
        ORDER BY seq ASC
        LIMIT ?
        """;
    private static final String LOCK_CONVERSATION_SQL = """
        SELECT type, latest_seq
        FROM conversations
        WHERE id = ?
        FOR UPDATE
        """;
    private static final String FIND_PRIVATE_CONVERSATION_SQL = """
        SELECT uid_1, uid_2, status
        FROM user_friendships
        WHERE id = ?
        """;
    private static final String INSERT_PRIVATE_MESSAGE_SQL = """
        INSERT INTO messages (
            msg_id, conversation_id, seq, client_msg_id, kind, sender_uid,
            peer_uid_low, peer_uid_high, group_id, server_ts_ms, payload_base64, message_type
        )
        VALUES (?, ?, ?, ?, 'private', ?, ?, ?, NULL, ?, ?, 'text')
        """;
    private static final String INSERT_GROUP_MESSAGE_SQL = """
        INSERT INTO messages (
            msg_id, conversation_id, seq, client_msg_id, kind, sender_uid,
            peer_uid_low, peer_uid_high, group_id, server_ts_ms, payload_base64, message_type
        )
        VALUES (?, ?, ?, ?, 'group', ?, NULL, NULL, ?, ?, ?, 'text')
        """;
    private static final String UPDATE_CONVERSATION_SQL = """
        UPDATE conversations
        SET latest_seq = ?, latest_message_time = ?, updated_at = now()
        WHERE id = ?
        """;

    private final DataSource dataSource;
    private final IdGenerator idGenerator;
    private final SessionService sessionService;
    private final ConversationStateService conversationStateService;
    private final Clock clock = Clock.systemUTC();

    public TextMessagesController(
        DataSource dataSource,
        IdGenerator idGenerator,
        SessionService sessionService,
        ConversationStateService conversationStateService
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.conversationStateService = Objects.requireNonNull(conversationStateService, "conversationStateService");
    }

    @Get
    public HttpResponse<?> list(
        @QueryValue String sessionId,
        long conversationId,
        @Nullable @QueryValue Long afterSeq,
        @Nullable @QueryValue Integer limit
    ) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }
        if (!conversationStateService.hasConversationAccess(conversationId, requesterUid.get())) {
            return HttpResponse.notFound();
        }

        int resolvedLimit = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_MESSAGES_SQL)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, afterSeq == null ? 0L : afterSeq);
            statement.setInt(3, resolvedLimit);
            return HttpResponse.ok(new MessagesResponse(mapMessages(statement.executeQuery())));
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to list messages", sqlException);
        }
    }

    @Post("/send-text/private")
    public HttpResponse<?> sendPrivate(@Body SendPrivateTextRequest request) {
        if (request == null) {
            return HttpResponse.badRequest(Map.of("message", "request body is required"));
        }
        return sendText(request.sessionId(), request.conversationId(), request.text(), "private");
    }

    @Post("/send-text/group")
    public HttpResponse<?> sendGroup(@Body SendGroupTextRequest request) {
        if (request == null) {
            return HttpResponse.badRequest(Map.of("message", "request body is required"));
        }
        return sendText(request.sessionId(), request.conversationId(), request.text(), "group");
    }

    private HttpResponse<?> sendText(String sessionId, long conversationId, String text, String expectedKind) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }
        if (text == null || text.trim().isEmpty()) {
            return HttpResponse.badRequest(Map.of("message", "text is required"));
        }
        if (!conversationStateService.hasConversationAccess(conversationId, requesterUid.get())) {
            return HttpResponse.notFound();
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ConversationLock conversation = lockConversation(connection, conversationId);
                if (!expectedKind.equals(conversation.kind())) {
                    connection.rollback();
                    return HttpResponse.badRequest(Map.of("message", "conversation kind mismatch"));
                }

                long msgId = idGenerator.nextId();
                long clientMsgId = idGenerator.nextId();
                long seq = conversation.latestSeq() + 1L;
                long serverTimeMs = clock.millis();
                String payloadBase64 = Base64.getEncoder().encodeToString(text.trim().getBytes(StandardCharsets.UTF_8));

                if ("private".equals(expectedKind)) {
                    PrivateRoute route = findPrivateRoute(connection, conversationId, requesterUid.get());
                    if (route == null) {
                        connection.rollback();
                        return HttpResponse.notFound();
                    }
                    insertPrivateMessage(connection, msgId, conversationId, seq, clientMsgId, requesterUid.get(), route, serverTimeMs, payloadBase64);
                } else {
                    insertGroupMessage(connection, msgId, conversationId, seq, clientMsgId, requesterUid.get(), serverTimeMs, payloadBase64);
                }
                updateConversation(connection, conversationId, seq, serverTimeMs);
                connection.commit();

                return HttpResponse.ok(new SendTextResponse(
                    new MessageItem(seq, msgId, conversationId, requesterUid.get(), serverTimeMs, text.trim(), "text")
                ));
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to send text message", sqlException);
        }
    }

    private ConversationLock lockConversation(Connection connection, long conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_CONVERSATION_SQL)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("conversation not found");
                }
                int type = resultSet.getInt(1);
                return new ConversationLock(type == 0 ? "private" : "group", resultSet.getLong(2));
            }
        }
    }

    private PrivateRoute findPrivateRoute(Connection connection, long conversationId, long requesterUid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_PRIVATE_CONVERSATION_SQL)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                long uid1 = resultSet.getLong(1);
                long uid2 = resultSet.getLong(2);
                if (!"ok".equals(resultSet.getString(3)) || (requesterUid != uid1 && requesterUid != uid2)) {
                    return null;
                }
                return new PrivateRoute(uid1, uid2);
            }
        }
    }

    private void insertPrivateMessage(
        Connection connection,
        long msgId,
        long conversationId,
        long seq,
        long clientMsgId,
        long senderUid,
        PrivateRoute route,
        long serverTimeMs,
        String payloadBase64
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PRIVATE_MESSAGE_SQL)) {
            statement.setLong(1, msgId);
            statement.setLong(2, conversationId);
            statement.setLong(3, seq);
            statement.setLong(4, clientMsgId);
            statement.setLong(5, senderUid);
            statement.setLong(6, route.uidLow());
            statement.setLong(7, route.uidHigh());
            statement.setLong(8, serverTimeMs);
            statement.setString(9, payloadBase64);
            statement.executeUpdate();
        }
    }

    private void insertGroupMessage(
        Connection connection,
        long msgId,
        long conversationId,
        long seq,
        long clientMsgId,
        long senderUid,
        long serverTimeMs,
        String payloadBase64
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_GROUP_MESSAGE_SQL)) {
            statement.setLong(1, msgId);
            statement.setLong(2, conversationId);
            statement.setLong(3, seq);
            statement.setLong(4, clientMsgId);
            statement.setLong(5, senderUid);
            statement.setLong(6, conversationId);
            statement.setLong(7, serverTimeMs);
            statement.setString(8, payloadBase64);
            statement.executeUpdate();
        }
    }

    private void updateConversation(Connection connection, long conversationId, long seq, long serverTimeMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_CONVERSATION_SQL)) {
            statement.setLong(1, seq);
            statement.setLong(2, serverTimeMs);
            statement.setLong(3, conversationId);
            statement.executeUpdate();
        }
    }

    private static List<MessageItem> mapMessages(ResultSet resultSet) throws SQLException {
        List<MessageItem> messages = new ArrayList<>();
        while (resultSet.next()) {
            messages.add(new MessageItem(
                resultSet.getLong(1),
                resultSet.getLong(2),
                resultSet.getLong(3),
                resultSet.getLong(4),
                resultSet.getLong(5),
                decodeText(resultSet.getString(6)),
                resultSet.getString(7)
            ));
        }
        return messages;
    }

    private static String decodeText(String payloadBase64) {
        try {
            return new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static HttpResponse<Map<String, String>> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "session invalid"));
    }

    public record SendPrivateTextRequest(String sessionId, long conversationId, String text) {
    }

    public record SendGroupTextRequest(String sessionId, long conversationId, String text) {
    }

    public record SendTextResponse(MessageItem message) {
    }

    public record MessagesResponse(List<MessageItem> items) {
    }

    public record MessageItem(long seq, long msgId, long conversationId, long senderUserId, long serverTimeMs, String text, String messageType) {
    }

    private record ConversationLock(String kind, long latestSeq) {
    }

    private record PrivateRoute(long uidLow, long uidHigh) {
    }
}
