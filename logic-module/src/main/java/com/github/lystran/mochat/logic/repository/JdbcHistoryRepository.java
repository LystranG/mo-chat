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
    private static final String FIND_HISTORY_WITH_RANGE_SQL = """
        SELECT seq, msg_id, server_ts_ms, payload_base64
        FROM messages
        WHERE conversation_id = ? AND seq BETWEEN ? AND ?
        ORDER BY seq DESC
        LIMIT ?
        """;

    private final DataSource dataSource;

    public JdbcHistoryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, int limit) {
        return findHistory(conversationId, cursorSeq, null, null, limit);
    }

    @Override
    public List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, Long startSeq, Long endSeq, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if ((startSeq == null) != (endSeq == null)) {
            throw new IllegalArgumentException("startSeq and endSeq must be provided together");
        }
        if (startSeq != null && startSeq > endSeq) {
            throw new IllegalArgumentException("startSeq must be <= endSeq");
        }

        try (Connection connection = dataSource.getConnection()) {
            if (startSeq != null) {
                return findWithRange(connection, conversationId, startSeq, endSeq, limit);
            }
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

    private List<HistoryMessage> findWithRange(Connection connection, long conversationId, long startSeq, long endSeq, int limit)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_HISTORY_WITH_RANGE_SQL)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, startSeq);
            statement.setLong(3, endSeq);
            // 范围查询仍按 seq 倒序截断窗口，语义是“该闭区间内最新的最多 50 条消息”。
            statement.setInt(4, limit);
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
