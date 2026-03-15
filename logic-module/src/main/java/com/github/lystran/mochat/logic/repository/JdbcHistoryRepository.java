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

/**
 * 基于 JDBC 读取会话历史消息。
 */
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

    // 注入 JDBC 数据源。
    public JdbcHistoryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    // 兼容老调用方：只传“从哪条消息之前继续查”这一种查法。
    public List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, int limit) {
        return findHistory(conversationId, cursorSeq, null, null, limit);
    }

    @Override
    // 根据传参决定：查最新一页、从某条之前继续查，还是直接查某一段消息。
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

    // 读取“某条消息之前”的那一页历史消息。
    private List<HistoryMessage> findWithCursor(Connection connection, long conversationId, long cursorSeq, int limit)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_HISTORY_WITH_CURSOR_SQL)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, cursorSeq);
            statement.setInt(3, limit);
            return mapMessages(statement.executeQuery());
        }
    }

    // 读取 startSeq 到 endSeq 这一段里的那一页消息。
    private List<HistoryMessage> findWithRange(Connection connection, long conversationId, long startSeq, long endSeq, int limit)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_HISTORY_WITH_RANGE_SQL)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, startSeq);
            statement.setLong(3, endSeq);
            // 就算是按一段消息去查，也还是先拿这段里最新的几条。
            statement.setInt(4, limit);
            return mapMessages(statement.executeQuery());
        }
    }

    // 读取会话里最新的一页历史消息。
    private List<HistoryMessage> findWithoutCursor(Connection connection, long conversationId, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_HISTORY_WITHOUT_CURSOR_SQL)) {
            statement.setLong(1, conversationId);
            statement.setInt(2, limit);
            return mapMessages(statement.executeQuery());
        }
    }

    // 把数据库结果一行行组装成历史消息列表。
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
