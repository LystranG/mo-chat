package com.github.lystran.mochat.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

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
