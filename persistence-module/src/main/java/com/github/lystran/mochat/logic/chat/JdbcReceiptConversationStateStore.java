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
    private final Cache<Long, ServerKnownPrivateConversation> serverKnownPrivateConversations;

    public JdbcReceiptConversationStateStore(DataSource dataSource) {
        this(dataSource, DEFAULT_SERVER_KNOWN_CACHE_MAX_SIZE);
    }

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

    long estimatedServerKnownPrivateConversationCount() {
        serverKnownPrivateConversations.cleanUp();
        return serverKnownPrivateConversations.estimatedSize();
    }

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

    private record ServerKnownPrivateConversation(long uidLow, long uidHigh, long latestSeq) {
    }
}
