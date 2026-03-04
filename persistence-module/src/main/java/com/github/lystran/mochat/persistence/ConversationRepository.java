package com.github.lystran.mochat.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

public final class ConversationRepository {
    private static final String UPDATE_LATEST_SQL = """
        UPDATE conversations
        SET latest_seq = ?, latest_message_time = ?, updated_at = now()
        WHERE id = ? AND latest_seq < ?
        """;

    private static final String EXISTS_SQL = """
        SELECT 1
        FROM conversations
        WHERE id = ?
        """;

    public void updateLatestState(Connection connection, long conversationId, long seq, long latestMessageTime)
        throws SQLException {
        Objects.requireNonNull(connection, "connection");

        try (PreparedStatement statement = connection.prepareStatement(UPDATE_LATEST_SQL)) {
            statement.setLong(1, seq);
            statement.setLong(2, latestMessageTime);
            statement.setLong(3, conversationId);
            statement.setLong(4, seq);
            int updated = statement.executeUpdate();
            if (updated > 0 || conversationExists(connection, conversationId)) {
                return;
            }
        }

        throw new SQLException("Conversation does not exist: " + conversationId);
    }

    private boolean conversationExists(Connection connection, long conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EXISTS_SQL)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
