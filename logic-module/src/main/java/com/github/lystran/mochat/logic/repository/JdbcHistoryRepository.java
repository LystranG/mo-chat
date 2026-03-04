package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Singleton
@Requires(beans = DataSource.class)
public final class JdbcHistoryRepository implements HistoryRepository {
    private static final String FIND_HISTORY_WITH_CURSOR_SQL = """
        SELECT seq, msg_id, server_ts_ms, payload_base64
        FROM messages
        WHERE conversation_id = ? AND seq < ?
        ORDER BY seq DESC
        LIMIT ?
        """;
    private static final String FIND_HISTORY_WITHOUT_CURSOR_SQL = """
        SELECT seq, msg_id, server_ts_ms, payload_base64
        FROM messages
        WHERE conversation_id = ?
        ORDER BY seq DESC
        LIMIT ?
        """;

    private final DataSource dataSource;

    public JdbcHistoryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }

        try (Connection connection = dataSource.getConnection()) {
            if (cursorSeq == null) {
                return findWithoutCursor(connection, conversationId, limit);
            }
            return findWithCursor(connection, conversationId, cursorSeq, limit);
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to query conversation history", sqlException);
        }
    }

    private List<HistoryMessage> findWithCursor(Connection connection, long conversationId, long cursorSeq, int limit)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_HISTORY_WITH_CURSOR_SQL)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, cursorSeq);
            statement.setInt(3, limit);
            return mapMessages(statement.executeQuery());
        }
    }

    private List<HistoryMessage> findWithoutCursor(Connection connection, long conversationId, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_HISTORY_WITHOUT_CURSOR_SQL)) {
            statement.setLong(1, conversationId);
            statement.setInt(2, limit);
            return mapMessages(statement.executeQuery());
        }
    }

    private List<HistoryMessage> mapMessages(ResultSet resultSet) throws SQLException {
        List<HistoryMessage> messages = new ArrayList<>();
        while (resultSet.next()) {
            messages.add(new HistoryMessage(
                resultSet.getLong(1),
                resultSet.getLong(2),
                resultSet.getLong(3),
                resultSet.getString(4)
            ));
        }
        return messages;
    }
}
