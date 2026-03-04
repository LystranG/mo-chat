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

    private final DataSource dataSource;

    public JdbcConversationStateRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
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
