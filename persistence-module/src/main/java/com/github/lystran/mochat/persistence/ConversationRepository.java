package com.github.lystran.mochat.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 负责更新 conversations 表里那几列“会话当前进度”，比如最新消息序号和私聊已送达位置。
 */
public final class ConversationRepository {
    private static final String UPDATE_LATEST_SQL = """
        UPDATE conversations
        SET latest_seq = ?, latest_message_time = ?, updated_at = now()
        WHERE id = ? AND latest_seq < ?
        """;
    private static final String UPDATE_UID_1_RECEIPT_SQL = """
        UPDATE conversations
        SET uid_1_seq = GREATEST(uid_1_seq, ?), updated_at = now()
        WHERE id = ?
        RETURNING uid_1_seq
        """;
    private static final String UPDATE_UID_2_RECEIPT_SQL = """
        UPDATE conversations
        SET uid_2_seq = GREATEST(uid_2_seq, ?), updated_at = now()
        WHERE id = ?
        RETURNING uid_2_seq
        """;

    // 更新会话的最新消息位置，只有新序号更大时才往前推。
    public void updateLatestState(Connection connection, long conversationId, long seq, long latestMessageTime)
        throws SQLException {
        Objects.requireNonNull(connection, "connection");

        try (PreparedStatement statement = connection.prepareStatement(UPDATE_LATEST_SQL)) {
            statement.setLong(1, seq);
            statement.setLong(2, latestMessageTime);
            statement.setLong(3, conversationId);
            statement.setLong(4, seq);
            statement.executeUpdate();
        }
    }

    // 更新私聊接收方“已经收到哪里”的位置，并把数据库确认后的结果返回给上层。
    public long updatePrivateReceiptSeq(
        Connection connection,
        long conversationId,
        long receiverUid,
        long peerUidLow,
        long peerUidHigh,
        long latestReceivedSeq
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");

        String sql;
        // 会话表把私聊双方固定放在 uid_1/uid_2 两列里，所以这里要先挑对该改哪一列。
        if (receiverUid == peerUidLow) {
            sql = UPDATE_UID_1_RECEIPT_SQL;
        } else if (receiverUid == peerUidHigh) {
            sql = UPDATE_UID_2_RECEIPT_SQL;
        } else {
            throw new IllegalArgumentException("receiver must be one of private conversation peers");
        }

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, latestReceivedSeq);
            statement.setLong(2, conversationId);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("conversation not found: " + conversationId);
                }
                return resultSet.getLong(1);
            }
        }
    }
}
