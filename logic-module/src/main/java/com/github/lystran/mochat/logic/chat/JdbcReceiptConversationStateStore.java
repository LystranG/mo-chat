package com.github.lystran.mochat.logic.chat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * 用数据库保存私聊的最新消息序号，以及双方各自确认到哪条消息。
 */
@Singleton
@Requires(beans = DataSource.class)
public final class JdbcReceiptConversationStateStore implements ReceiptConversationStateStore {
    private static final long DEFAULT_SERVER_KNOWN_CACHE_MAX_SIZE = 100_000L;
    private static final String FIND_PRIVATE_CONVERSATION_SQL = """
        SELECT c.id, f.uid_1, f.uid_2, c.latest_seq, c.uid_1_seq, c.uid_2_seq
        FROM conversations c
        JOIN user_friendships f ON f.id = c.id
        WHERE c.id = ? AND c.type = 0
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

    private final DataSource dataSource;
    // 这份缓存先记住服务端刚分配出去、但数据库可能还没追上的最新 seq，补上发送和异步落库之间的时间差。
    private final Cache<Long, ServerKnownPrivateConversation> serverKnownPrivateConversations;

    /**
     * 使用默认缓存大小构造数据库版私聊确认状态仓储。
     */
    public JdbcReceiptConversationStateStore(DataSource dataSource) {
        this(dataSource, DEFAULT_SERVER_KNOWN_CACHE_MAX_SIZE);
    }

    /**
     * 使用指定缓存大小构造数据库版私聊确认状态仓储。
     */
    JdbcReceiptConversationStateStore(DataSource dataSource, long serverKnownCacheMaxSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (serverKnownCacheMaxSize <= 0) {
            throw new IllegalArgumentException("serverKnownCacheMaxSize must be > 0");
        }
        this.serverKnownPrivateConversations = Caffeine.newBuilder()
            .maximumSize(serverKnownCacheMaxSize)
            .build();
    }

    /**
     * 读取私聊状态；如果内存里记着更新的最新 seq，就优先用那份。
     */
    @Override
    public Optional<PrivateConversationState> findPrivateConversation(long conversationId) {
        try (Connection connection = dataSource.getConnection()) {
            Optional<PrivateConversationState> persistedState = findPrivateConversation(connection, conversationId);
            if (persistedState.isEmpty()) {
                return Optional.empty();
            }

            PrivateConversationState state = persistedState.get();
            ServerKnownPrivateConversation serverKnown = serverKnownPrivateConversations.getIfPresent(conversationId);
            if (serverKnown == null) {
                return persistedState;
            }
            if (serverKnown.uidLow() != state.uidLow() || serverKnown.uidHigh() != state.uidHigh()) {
                throw new IllegalStateException("conversation participants mismatch");
            }
            // 发消息时服务端可能先知道最新 seq，数据库稍后才追上，所以这里优先保留内存里那份更新的值。
            if (serverKnown.latestSeq() <= state.latestSeq()) {
                return persistedState;
            }

            return Optional.of(
                new PrivateConversationState(
                    state.conversationId(),
                    state.uidLow(),
                    state.uidHigh(),
                    serverKnown.latestSeq(),
                    state.uidLowSeq(),
                    state.uidHighSeq()
                )
            );
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to load private conversation state", sqlException);
        }
    }

    /**
     * 刷新服务端内存里记住的私聊最新消息序号。
     */
    @Override
    public void upsertPrivateConversation(long conversationId, long uidLow, long uidHigh, long latestSeq) {
        if (uidLow <= 0 || uidHigh <= 0 || uidLow >= uidHigh) {
            throw new IllegalArgumentException("private conversation participants must be ordered and positive");
        }
        if (latestSeq < 0) {
            throw new IllegalArgumentException("latestSeq must be >= 0");
        }
        serverKnownPrivateConversations.asMap().compute(conversationId, (ignored, current) -> {
            if (current == null) {
                return new ServerKnownPrivateConversation(uidLow, uidHigh, latestSeq);
            }
                if (current.uidLow() != uidLow || current.uidHigh() != uidHigh) {
                    throw new IllegalStateException("conversation participants mismatch");
                }
                // 只接受更大的 latestSeq，避免旧值把新值盖回去。
                if (latestSeq <= current.latestSeq()) {
                    return current;
                }
            return new ServerKnownPrivateConversation(uidLow, uidHigh, latestSeq);
        });
    }

    /**
     * 返回当前缓存里大概记了多少条私聊状态，主要给测试观察用。
     */
    long estimatedServerKnownPrivateConversationCount() {
        serverKnownPrivateConversations.cleanUp();
        return serverKnownPrivateConversations.estimatedSize();
    }

    /**
     * 把接收方“已经确认收到哪条消息”写回 conversations 表。
     */
    @Override
    public long updateLatestReceivedSeq(long conversationId, long receiverUid, long latestReceivedSeq) {
        if (latestReceivedSeq < 0) {
            throw new IllegalArgumentException("latestReceivedSeq must be >= 0");
        }

        try (Connection connection = dataSource.getConnection()) {
            PrivateConversationState state = findPrivateConversation(connection, conversationId)
                .orElseThrow(() -> new IllegalArgumentException("conversation not found"));

            final String updateSql;
            if (receiverUid == state.uidLow()) {
                updateSql = UPDATE_UID_1_RECEIPT_SQL;
            } else if (receiverUid == state.uidHigh()) {
                updateSql = UPDATE_UID_2_RECEIPT_SQL;
            } else {
                throw new IllegalArgumentException("receiver is not a conversation participant");
            }

            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                // 这里用 GREATEST 只保留更大的值，重复确认或乱序确认都不会把进度写回去。
                statement.setLong(1, latestReceivedSeq);
                statement.setLong(2, conversationId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("conversation not found: " + conversationId);
                    }
                    return resultSet.getLong(1);
                }
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to persist receipt sequence", sqlException);
        }
    }

    /**
     * 在给定连接上查询私聊会话的当前持久化状态。
     */
    private Optional<PrivateConversationState> findPrivateConversation(Connection connection, long conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_PRIVATE_CONVERSATION_SQL)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                return Optional.of(
                    new PrivateConversationState(
                        resultSet.getLong(1),
                        resultSet.getLong(2),
                        resultSet.getLong(3),
                        resultSet.getLong(4),
                        resultSet.getLong(5),
                        resultSet.getLong(6)
                    )
                );
            }
        }
    }

    /**
     * 保存服务端内存里记住的私聊参与者和最新消息序号。
     */
    private record ServerKnownPrivateConversation(long uidLow, long uidHigh, long latestSeq) {
    }
}
