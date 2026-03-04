package com.github.lystran.mochat.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class MqConsumer {
    private final DataSource dataSource;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;

    public MqConsumer(
        DataSource dataSource,
        MessageRepository messageRepository,
        ConversationRepository conversationRepository
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
    }

    public void persistMessage(MessageRepository.PersistedMessage message) throws SQLException {
        Objects.requireNonNull(message, "message");

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                messageRepository.insert(connection, message);
                conversationRepository.updateLatestState(
                    connection,
                    message.conversationId(),
                    message.seq(),
                    message.serverTsMs()
                );
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }
}
