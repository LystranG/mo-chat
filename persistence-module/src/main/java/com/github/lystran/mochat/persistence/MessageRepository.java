package com.github.lystran.mochat.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.Objects;

/**
 * 负责把消息正文写进 messages 表，并区分“新消息”和“重复重试”。
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
            height,
            waveform_data
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            payload_base64,
            message_type,
            media_url,
            thumbnail_url,
            file_size,
            mime_type,
            file_name,
            duration,
            width,
            height,
            waveform_data
        FROM messages
        WHERE msg_id = ?
        """;

    /**
     * 尝试写入一条消息；如果数据库里已经有同一个 msgId，就判断是不是同一条重试。
     */
    public InsertResult insert(Connection connection, PersistedMessage message) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");

        // 先在当前事务里做一次可回退的插入尝试，失败后还能继续查库里已有内容。
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
                statement.setString(12, message.messageType());
                statement.setObject(13, message.mediaUrl(), Types.VARCHAR);
                statement.setObject(14, message.thumbnailUrl(), Types.VARCHAR);
                statement.setObject(15, message.fileSize(), Types.BIGINT);
                statement.setObject(16, message.mimeType(), Types.VARCHAR);
                statement.setObject(17, message.fileName(), Types.VARCHAR);
                statement.setObject(18, message.duration(), Types.INTEGER);
                statement.setObject(19, message.width(), Types.INTEGER);
                statement.setObject(20, message.height(), Types.INTEGER);
                statement.setObject(21, message.waveformData(), Types.VARCHAR);
                statement.executeUpdate();
            }
            return InsertResult.INSERTED;
        } catch (SQLException exception) {
            rollbackToSavepoint(connection, insertAttempt, exception);
            // msgId 已存在时，再把旧记录读出来看看：如果内容完全一样，就把它当成“重复重试”。
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

    /**
     * 按 msgId 读出数据库里已经存在的那条消息。
     */
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
                    resultSet.getString("payload_base64"),
                        resultSet.getString("message_type"),
                        resultSet.getString("media_url"),
                        resultSet.getString("thumbnail_url"),
                        resultSet.getObject("file_size", Long.class),
                        resultSet.getString("mime_type"),
                        resultSet.getString("file_name"),
                        resultSet.getObject("duration", Integer.class),
                        resultSet.getObject("width", Integer.class),
                        resultSet.getObject("height", Integer.class),
                        resultSet.getString("waveform_data")
                );
            }
        } catch (SQLException lookupFailure) {
            failure.addSuppressed(lookupFailure);
            throw failure;
        }
    }

    /**
     * 把事务回退到本次插入前的保存点，避免整个外层事务被直接打断。
     */
    private static void rollbackToSavepoint(Connection connection, Savepoint savepoint, SQLException failure) throws SQLException {
        try {
            connection.rollback(savepoint);
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            throw failure;
        }
    }

    /**
     * 表示本次写库是新插入，还是碰到了数据库里已经存在的同一条消息。
     */
    public enum InsertResult {
        INSERTED,
        DURABLE_DUPLICATE
    }

    /**
     * 表示已经整理好、准备写进 messages 表的一条消息。
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
        String payloadBase64,
        String messageType,
        String mediaUrl,
        String thumbnailUrl,
        Long fileSize,
        String mimeType,
        String fileName,
        Integer duration,
        Integer width,
        Integer height,
        String waveformData
    ) {
        /**
         * 在构造时拦住最基本的坏数据。
         */
        public PersistedMessage {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payloadBase64, "payloadBase64");
            Objects.requireNonNull(messageType, "messageType");
        }
    }
}
