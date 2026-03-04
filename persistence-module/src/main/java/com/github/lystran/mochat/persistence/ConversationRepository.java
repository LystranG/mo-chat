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
}
