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
 * 基于 JDBC 的历史消息查询实现。
 */
@Singleton
@Requires(beans = DataSource.class)
public final class JdbcHistoryRepository implements HistoryRepository {
    // 游标模式：从某条消息之前继续往前翻。
    private static final String FIND_HISTORY_WITH_CURSOR_SQL = """
        SELECT seq, msg_id, sender_uid, server_ts_ms, payload_base64
        FROM messages
        WHERE conversation_id = ? AND seq < ?
        ORDER BY seq DESC
        LIMIT ?
        """;
    // 默认模式：不带游标时直接取最新一页。
    private static final String FIND_HISTORY_WITHOUT_CURSOR_SQL = """
        SELECT seq, msg_id, sender_uid, server_ts_ms, payload_base64
        FROM messages
        WHERE conversation_id = ?
        ORDER BY seq DESC
        LIMIT ?
        """;
    // 区间模式：只看 startSeq 到 endSeq 之间这段消息。
    private static final String FIND_HISTORY_WITH_RANGE_SQL = """
        SELECT seq, msg_id, sender_uid, server_ts_ms, payload_base64
        FROM messages
        WHERE conversation_id = ? AND seq BETWEEN ? AND ?
        ORDER BY seq DESC
        LIMIT ?
        """;

    private final DataSource dataSource;

    /**
     * 创建 JDBC 历史消息仓储。
     */
    public JdbcHistoryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 按游标模式查询历史消息。
     */
    @Override
    public List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, int limit) {
        return findHistory(conversationId, cursorSeq, null, null, limit);
    }

    /**
     * 按默认、游标或闭区间模式查询历史消息。
     */
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
                // 闭区间模式优先，适合补某一段确定范围的历史。
                return findWithRange(connection, conversationId, startSeq, endSeq, limit);
            }
            if (cursorSeq == null) {
                // 没有游标就取最新一页。
                return findWithoutCursor(connection, conversationId, limit);
            }
            // 带游标时继续往更早的消息翻页。
            return findWithCursor(connection, conversationId, cursorSeq, limit);
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to query conversation history", sqlException);
        }
    }

    /**
     * 按“某条消息之前”的方式取一页历史。
     */
    private List<HistoryMessage> findWithCursor(Connection connection, long conversationId, long cursorSeq, int limit)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_HISTORY_WITH_CURSOR_SQL)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, cursorSeq);
            statement.setInt(3, limit);
            return mapMessages(statement.executeQuery());
        }
    }

    /**
     * 按闭区间取一页历史。
     */
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

    /**
     * 取会话最新的一页历史消息。
     */
    private List<HistoryMessage> findWithoutCursor(Connection connection, long conversationId, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_HISTORY_WITHOUT_CURSOR_SQL)) {
            statement.setLong(1, conversationId);
            statement.setInt(2, limit);
            return mapMessages(statement.executeQuery());
        }
    }

    /**
     * 把数据库结果整理成历史消息列表。
     */
    private List<HistoryMessage> mapMessages(ResultSet resultSet) throws SQLException {
        List<HistoryMessage> messages = new ArrayList<>();
        while (resultSet.next()) {
            messages.add(new HistoryMessage(
                resultSet.getLong(1),
                resultSet.getLong(2),
                resultSet.getLong(3),
                resultSet.getLong(4),
                resultSet.getString(5)
            ));
        }
        return messages;
    }
}
