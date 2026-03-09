package com.github.lystran.mochat.persistence;

import com.github.lystran.mochat.persistence.cache.GroupMessageCache;
import io.lettuce.core.api.sync.RedisCommands;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
class TransactionalPersistenceTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private MqConsumer mqConsumer;
    private ConversationRepository conversationRepository;

    @BeforeEach
    void setUp() {
        Flyway.configure()
            .cleanDisabled(false)
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .clean();

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        mqConsumer = new MqConsumer(
            dataSource(),
            new MessageRepository(),
            new ConversationRepository(),
            new GroupMessageCache(mock(RedisCommands.class))
        );
        conversationRepository = new ConversationRepository();
    }

    @Test
    void updatesConversationLatestSeqOnlyWhenNewSeqIsHigher() throws SQLException {
        insertConversation(42L, 5L, 1_000L);

        mqConsumer.persistMessage(privateMessage(1L, 42L, 6L, 2_000L, 11L));
        mqConsumer.persistMessage(privateMessage(2L, 42L, 4L, 3_000L, 12L));

        assertEquals(new ConversationState(6L, 2_000L), conversationState(42L));
    }

    @Test
    void rollsBackMessageInsertWhenConversationUpdateFails() throws SQLException {
        long msgId = 1001L;

        SQLException exception = assertThrows(
            SQLException.class,
            () -> mqConsumer.persistMessage(privateMessage(msgId, 999L, 1L, 1_500L, 50L))
        );

        assertTrue(exception.getMessage().contains("messages_conversation_fk"));
        assertEquals(0, messageCount(msgId));
    }

    @Test
    void privateReceiptSeqIsUpdatedMonotonicallyWithGreatest() throws SQLException {
        insertConversation(42L, 20L, 1_000L);

        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        )) {
            long first = conversationRepository.updatePrivateReceiptSeq(connection, 42L, 200L, 100L, 200L, 7L);
            long second = conversationRepository.updatePrivateReceiptSeq(connection, 42L, 200L, 100L, 200L, 5L);

            assertEquals(7L, first);
            assertEquals(7L, second);
        }

        assertEquals(new ReceiptState(0L, 7L), receiptState(42L));
    }

    @Test
    void groupCacheUpdateSeesCommittedGroupMessage() throws Exception {
        insertConversation(77L, 13L, 3_000L);
        GroupMessageCache groupMessageCache = mock(GroupMessageCache.class);
        AtomicInteger visibleMessagesWhenCacheRuns = new AtomicInteger(-1);
        MessageRepository.PersistedMessage message = groupMessage(9L, 77L, 14L, 3_100L, 91L, 5001L);

        doAnswer(invocation -> {
            visibleMessagesWhenCacheRuns.set(messageCount(message.msgId()));
            return null;
        }).when(groupMessageCache).cache(any(MessageRepository.PersistedMessage.class));

        mqConsumer = new MqConsumer(dataSource(), new MessageRepository(), new ConversationRepository(), groupMessageCache);

        assertDoesNotThrow(() -> mqConsumer.persistMessage(message));

        assertEquals(1, visibleMessagesWhenCacheRuns.get());
        verify(groupMessageCache).cache(message);
        assertEquals(1, messageCount(message.msgId()));
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private void insertConversation(long conversationId, long latestSeq, long latestMessageTime) throws SQLException {
        String sql = """
            INSERT INTO conversations (id, type, latest_seq, latest_message_time, uid_1_seq, uid_2_seq)
            VALUES (?, 0, ?, ?, 0, 0)
            """;

        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, latestSeq);
            statement.setLong(3, latestMessageTime);
            statement.executeUpdate();
        }
    }

    private ConversationState conversationState(long conversationId) throws SQLException {
        String sql = """
            SELECT latest_seq, latest_message_time
            FROM conversations
            WHERE id = ?
            """;

        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new ConversationState(resultSet.getLong(1), resultSet.getLong(2));
            }
        }
    }

    private ReceiptState receiptState(long conversationId) throws SQLException {
        String sql = """
            SELECT uid_1_seq, uid_2_seq
            FROM conversations
            WHERE id = ?
            """;

        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new ReceiptState(resultSet.getLong(1), resultSet.getLong(2));
            }
        }
    }

    private int messageCount(long msgId) throws SQLException {
        String sql = """
            SELECT COUNT(*)
            FROM messages
            WHERE msg_id = ?
            """;

        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, msgId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
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

    private static MessageRepository.PersistedMessage groupMessage(
        long msgId,
        long conversationId,
        long seq,
        long serverTsMs,
        long clientMsgId,
        long groupId
    ) {
        return new MessageRepository.PersistedMessage(
            msgId,
            conversationId,
            seq,
            clientMsgId,
            "group",
            100L,
            null,
            null,
            groupId,
            serverTsMs,
            "Z3JvdXAtcGF5bG9hZA=="
        );
    }

    private record ConversationState(long latestSeq, long latestMessageTime) {
    }

    private record ReceiptState(long uid1Seq, long uid2Seq) {
    }
}
