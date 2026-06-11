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
            payload_base64,
            message_type,
            media_url,
            thumbnail_url,
            file_size,
            mime_type,
            file_name,
            duration,
            width,
            height
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

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
            statement.setString(12, message.messageType());
            statement.setObject(13, message.mediaUrl(), Types.VARCHAR);
            statement.setObject(14, message.thumbnailUrl(), Types.VARCHAR);
            statement.setObject(15, message.fileSize(), Types.BIGINT);
            statement.setObject(16, message.mimeType(), Types.VARCHAR);
            statement.setObject(17, message.fileName(), Types.VARCHAR);
            statement.setObject(18, message.duration(), Types.INTEGER);
            statement.setObject(19, message.width(), Types.INTEGER);
            statement.setObject(20, message.height(), Types.INTEGER);
            statement.executeUpdate();
        }
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
        String payloadBase64,
        String messageType,
        String mediaUrl,
        String thumbnailUrl,
        Long fileSize,
        String mimeType,
        String fileName,
        Integer duration,
        Integer width,
        Integer height
    ) {
        public PersistedMessage {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payloadBase64, "payloadBase64");
            Objects.requireNonNull(messageType, "messageType");
            
            if (!"text".equals(messageType) && mediaUrl == null) {
                throw new IllegalArgumentException("mediaUrl is required for non-text messages");
            }
            if ("text".equals(messageType) && mediaUrl != null) {
                throw new IllegalArgumentException("mediaUrl must be null for text messages");
            }
        }
    }
}
