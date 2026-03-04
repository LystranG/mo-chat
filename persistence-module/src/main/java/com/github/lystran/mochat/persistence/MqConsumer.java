package com.github.lystran.mochat.persistence;

import com.github.lystran.mochat.persistence.cache.GroupMessageCache;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class MqConsumer {
    private final DataSource dataSource;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final GroupMessageCache groupMessageCache;

    public MqConsumer(
        DataSource dataSource,
        MessageRepository messageRepository,
        ConversationRepository conversationRepository,
        GroupMessageCache groupMessageCache
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.groupMessageCache = Objects.requireNonNull(groupMessageCache, "groupMessageCache");
    }

    public void persistMessage(MessageRepository.PersistedMessage message) throws SQLException {
        Objects.requireNonNull(message, "message");
        boolean committed = false;

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            Throwable failure = null;
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
                committed = true;
            } catch (SQLException | RuntimeException exception) {
                failure = exception;
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit, failure);
            }
        }

        if (committed && isGroupMessage(message)) {
            groupMessageCache.cache(message);
        }
    }

    private static boolean isGroupMessage(MessageRepository.PersistedMessage message) {
        return message.groupId() != null && "group".equals(message.kind());
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit, Throwable failure) throws SQLException {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException resetException) {
            if (failure != null) {
                failure.addSuppressed(resetException);
                return;
            }
            // Transaction was already committed; avoid surfacing cleanup-only failures.
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
