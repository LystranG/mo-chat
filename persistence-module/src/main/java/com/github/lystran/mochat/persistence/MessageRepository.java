package com.github.lystran.mochat.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/**
 * 负责把单条消息原样写进 messages 表。
 */
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

    // 插入一整行消息数据，写进去的列和 messages 表里的列是一一对应的。
    public void insert(Connection connection, PersistedMessage message) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");

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
    }

    /**
     * 表示已经整理成“可以直接写数据库”的消息数据。
     */
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
        // 先拦住最基本的空字段，避免把半截消息直接交给 JDBC 去写。
        public PersistedMessage {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payloadBase64, "payloadBase64");
        }
    }
}
