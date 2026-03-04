package com.github.lystran.mochat.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqConsumerTransactionTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final Connection connection = mock(Connection.class);

    private MqConsumer mqConsumer;

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        mqConsumer = new MqConsumer(dataSource, messageRepository, conversationRepository);
    }

    @Test
    void commitsAndUpdatesConversationWhenRepositoriesSucceed() throws SQLException {
        MessageRepository.PersistedMessage message = privateMessage(1L, 42L, 6L, 2_000L, 11L);

        mqConsumer.persistMessage(message);

        InOrder callOrder = inOrder(connection, messageRepository, conversationRepository);
        callOrder.verify(connection).setAutoCommit(false);
        callOrder.verify(messageRepository).insert(connection, message);
        callOrder.verify(conversationRepository).updateLatestState(connection, 42L, 6L, 2_000L);
        callOrder.verify(connection).commit();
        callOrder.verify(connection).setAutoCommit(true);
        verify(connection, never()).rollback();
    }

    @Test
    void rollsBackAndRethrowsWhenMessageInsertFails() throws SQLException {
        MessageRepository.PersistedMessage message = privateMessage(2L, 77L, 1L, 3_000L, 12L);
        SQLException failure = new SQLException("insert failed");
        doThrow(failure).when(messageRepository).insert(connection, message);

        SQLException thrown = assertThrows(SQLException.class, () -> mqConsumer.persistMessage(message));

        assertSame(failure, thrown);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void doesNotFailAfterCommitWhenAutoCommitResetFails() throws SQLException {
        MessageRepository.PersistedMessage message = privateMessage(3L, 55L, 9L, 9_000L, 13L);
        doThrow(new SQLException("cannot restore auto-commit")).when(connection).setAutoCommit(true);

        assertDoesNotThrow(() -> mqConsumer.persistMessage(message));
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    private static MessageRepository.PersistedMessage privateMessage(
        long msgId,
        long conversationId,
        long seq,
        long serverTsMs,
        long clientMsgId
    ) {
        return new MessageRepository.PersistedMessage(
            msgId,
            conversationId,
            seq,
            clientMsgId,
            "private",
            100L,
            100L,
            200L,
            null,
            serverTsMs,
            "cGF5bG9hZA=="
        );
    }
}
