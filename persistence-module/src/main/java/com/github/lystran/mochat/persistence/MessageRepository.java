package com.github.lystran.mochat.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.Objects;

public final class MessageRepository {
    private static final String INSERT_SQL = """
        INSERT INTO messages (
            msg_id,
            conversation_id,
            seq,
            client_msg_id,
            kind,
            sender_uid,
            peer_uid_low,
            peer_uid_high,
            group_id,
            server_ts_ms,
            payload_base64
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    private static final String SELECT_BY_MSG_ID_SQL = """
        SELECT
            msg_id,
            conversation_id,
            seq,
            client_msg_id,
            kind,
            sender_uid,
            peer_uid_low,
            peer_uid_high,
            group_id,
            server_ts_ms,
            payload_base64
        FROM messages
        WHERE msg_id = ?
        """;

    public InsertResult insert(Connection connection, PersistedMessage message) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");

        Savepoint insertAttempt = connection.setSavepoint();
        try {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                statement.setLong(1, message.msgId());
                statement.setLong(2, message.conversationId());
                statement.setLong(3, message.seq());
                statement.setLong(4, message.clientMsgId());
                statement.setString(5, message.kind());
                statement.setLong(6, message.senderUid());
                statement.setObject(7, message.peerUidLow(), Types.BIGINT);
                statement.setObject(8, message.peerUidHigh(), Types.BIGINT);
                statement.setObject(9, message.groupId(), Types.BIGINT);
                statement.setLong(10, message.serverTsMs());
                statement.setString(11, message.payloadBase64());
                statement.executeUpdate();
            }
            return InsertResult.INSERTED;
        } catch (SQLException exception) {
            rollbackToSavepoint(connection, insertAttempt, exception);
            PersistedMessage existing = loadByMsgId(connection, message.msgId(), exception);
            if (message.equals(existing)) {
                return InsertResult.DURABLE_DUPLICATE;
            }
            if (existing != null) {
                throw new DurableMessageConflictException(message, existing, exception);
            }
            throw exception;
        }
    }

    private static PersistedMessage loadByMsgId(Connection connection, long msgId, SQLException failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_MSG_ID_SQL)) {
            statement.setLong(1, msgId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new PersistedMessage(
                    resultSet.getLong("msg_id"),
                    resultSet.getLong("conversation_id"),
                    resultSet.getLong("seq"),
                    resultSet.getLong("client_msg_id"),
                    resultSet.getString("kind"),
                    resultSet.getLong("sender_uid"),
                    resultSet.getObject("peer_uid_low", Long.class),
                    resultSet.getObject("peer_uid_high", Long.class),
                    resultSet.getObject("group_id", Long.class),
                    resultSet.getLong("server_ts_ms"),
                    resultSet.getString("payload_base64")
                );
            }
        } catch (SQLException lookupFailure) {
            failure.addSuppressed(lookupFailure);
            throw failure;
        }
    }

    private static void rollbackToSavepoint(Connection connection, Savepoint savepoint, SQLException failure) throws SQLException {
        try {
            connection.rollback(savepoint);
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            throw failure;
        }
    }

    public enum InsertResult {
        INSERTED,
        DURABLE_DUPLICATE
    }

    public record PersistedMessage(
        long msgId,
        long conversationId,
        long seq,
        long clientMsgId,
        String kind,
        long senderUid,
        Long peerUidLow,
        Long peerUidHigh,
        Long groupId,
        long serverTsMs,
        String payloadBase64
    ) {
        public PersistedMessage {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payloadBase64, "payloadBase64");
        }
    }
}
