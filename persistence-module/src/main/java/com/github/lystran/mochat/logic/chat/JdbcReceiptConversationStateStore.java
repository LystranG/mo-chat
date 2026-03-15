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
 * 用数据库加一层进程内缓存，记录私聊双方已经确认到哪条消息。
 */
@Singleton
@Requires(beans = DataSource.class)
@Requires(property = "micronaut.application.name", notEquals = "message-service", defaultValue = "")
@Requires(property = "micronaut.application.name", notEquals = "mochat", defaultValue = "")
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
    // 进程内记住“服务自己已经看见的最新私聊进度”，避免数据库短暂落后时把进度读旧。
    private final Cache<Long, ServerKnownPrivateConversation> serverKnownPrivateConversations;

    /**
     * 用默认缓存大小创建私聊确认进度仓储。
     */
    public JdbcReceiptConversationStateStore(DataSource dataSource) {
        this(dataSource, DEFAULT_SERVER_KNOWN_CACHE_MAX_SIZE);
    }

    /**
     * 用指定缓存大小创建私聊确认进度仓储。
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

    @Override
    /**
     * 查一段私聊当前的最新消息位置，以及双方各自确认到哪条消息。
     */
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
            // 如果数据库还没追上进程里刚刚记住的最新 seq，就先把更新的那份最新值带回去。
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

    @Override
    /**
     * 把这段私聊最新消息位置记进进程内缓存，供短时间内的读请求直接复用。
     */
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
            if (latestSeq <= current.latestSeq()) {
                return current;
            }
            return new ServerKnownPrivateConversation(uidLow, uidHigh, latestSeq);
        });
    }

    /**
     * 返回进程内大概记住了多少段私聊进度，主要给测试观察用。
     */
    long estimatedServerKnownPrivateConversationCount() {
        serverKnownPrivateConversations.cleanUp();
        return serverKnownPrivateConversations.estimatedSize();
    }

    @Override
    /**
     * 把某一侧“已经收到哪条消息”的进度写回数据库，并返回更新后的值。
     */
    public long updateLatestReceivedSeq(long conversationId, long receiverUid, long latestReceivedSeq) {
        if (latestReceivedSeq < 0) {
            throw new IllegalArgumentException("latestReceivedSeq must be >= 0");
        }

        try (Connection connection = dataSource.getConnection()) {
            PrivateConversationState state = findPrivateConversation(connection, conversationId)
                .orElseThrow(() -> new IllegalArgumentException("conversation not found"));

            final String updateSql;
            // conversations 表把私聊两侧固定存成 uid_1_seq / uid_2_seq，所以这里先找这次该改哪一列。
            if (receiverUid == state.uidLow()) {
                updateSql = UPDATE_UID_1_RECEIPT_SQL;
            } else if (receiverUid == state.uidHigh()) {
                updateSql = UPDATE_UID_2_RECEIPT_SQL;
            } else {
                throw new IllegalArgumentException("receiver is not a conversation participant");
            }

            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
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
     * 用同一个数据库连接读出一段私聊的当前状态。
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
     * 表示服务进程自己记住的一份“最新私聊进度”。
     */
    private record ServerKnownPrivateConversation(long uidLow, long uidHigh, long latestSeq) {
    }
}
