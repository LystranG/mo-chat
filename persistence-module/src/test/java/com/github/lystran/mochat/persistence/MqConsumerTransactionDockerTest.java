package com.github.lystran.mochat.persistence;

import com.github.lystran.mochat.persistence.cache.GroupMessageCache;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
class MqConsumerTransactionDockerTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void duplicateGroupMessageReplayDoesNotAdvanceConversationOrReplayCache() throws Exception {
        resetAndMigrate();
        insertConversation(77L, 13L, 3_000L);

        ConversationRepository conversationRepository = spy(new ConversationRepository());
        GroupMessageCache groupMessageCache = mock(GroupMessageCache.class);
        MqConsumer mqConsumer = new MqConsumer(
            dataSource(),
            new MessageRepository(),
            conversationRepository,
            groupMessageCache
        );
        MessageRepository.PersistedMessage message = new MessageRepository.PersistedMessage(
            9L,
            77L,
            14L,
            91L,
            "group",
            100L,
            null,
            null,
            5001L,
            3_100L,
            "Z3JvdXAtcGF5bG9hZA=="
        );

        assertDoesNotThrow(() -> mqConsumer.persistMessage(message));
        assertDoesNotThrow(() -> mqConsumer.persistMessage(message));

        verify(conversationRepository, times(1))
            .updateLatestState(any(Connection.class), eq(77L), eq(14L), eq(3_100L));
        verify(groupMessageCache, times(1)).cache(message);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static void resetAndMigrate() {
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
    }

    private static void insertConversation(long conversationId, long latestSeq, long latestMessageTime) throws Exception {
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
}
