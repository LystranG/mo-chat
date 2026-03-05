package com.github.lystran.mochat.logic.repository;

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
public final class JdbcConversationStateRepository implements ConversationStateRepository {
    private static final String FIND_PRIVATE_RECEIPT_STATE_SQL = """
        SELECT f.uid_1, f.uid_2, c.uid_1_seq, c.uid_2_seq
        FROM conversations c
        JOIN user_friendships f ON f.id = c.id
        WHERE c.id = ? AND c.type = 0
        """;
    private static final String FIND_CONVERSATION_LATEST_STATE_SQL = """
        SELECT id, latest_seq, latest_message_time
        FROM conversations
        WHERE id = ?
        """;
    private static final String HAS_CONVERSATION_ACCESS_SQL = """
        SELECT EXISTS (
            SELECT 1
            FROM conversations c
            WHERE c.id = ?
              AND (
                (
                    c.type = 0
                    AND EXISTS (
                        SELECT 1
                        FROM user_friendships f
                        WHERE f.id = c.id
                          AND (? = f.uid_1 OR ? = f.uid_2)
                    )
                )
                OR
                (
                    c.type = 1
                    AND EXISTS (
                        SELECT 1
                        FROM group_memberships gm
                        WHERE gm.group_id = c.id
                          AND gm.user_id = ?
                          AND gm.status = 'active'
                    )
                )
              )
        )
        """;

    private final DataSource dataSource;

    public JdbcConversationStateRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean hasConversationAccess(long conversationId, long requesterUid) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(HAS_CONVERSATION_ACCESS_SQL)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, requesterUid);
            statement.setLong(3, requesterUid);
            statement.setLong(4, requesterUid);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                return resultSet.getBoolean(1);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to verify conversation access", sqlException);
        }
    }

    @Override
    public Optional<Long> findPrivatePeerLatestReceivedSeq(long conversationId, long requesterUid) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_PRIVATE_RECEIPT_STATE_SQL)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                long uidLow = resultSet.getLong(1);
                long uidHigh = resultSet.getLong(2);
                long uidLowSeq = resultSet.getLong(3);
                long uidHighSeq = resultSet.getLong(4);
                if (requesterUid == uidLow) {
                    return Optional.of(uidHighSeq);
                }
                if (requesterUid == uidHigh) {
                    return Optional.of(uidLowSeq);
                }
                return Optional.empty();
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to query private peer latest receipt sequence", sqlException);
        }
    }

    @Override
    public Optional<ConversationLatestState> findConversationLatestState(long conversationId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_CONVERSATION_LATEST_STATE_SQL)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                return Optional.of(
                    new ConversationLatestState(
                        resultSet.getLong(1),
                        resultSet.getLong(2),
                        resultSet.getLong(3)
                    )
                );
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to query conversation latest state", sqlException);
        }
    }
}
