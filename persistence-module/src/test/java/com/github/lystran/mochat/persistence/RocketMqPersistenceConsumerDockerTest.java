package com.github.lystran.mochat.persistence;

import com.github.lystran.mochat.persistence.cache.GroupMessageCache;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class RocketMqPersistenceConsumerDockerTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void returnsSuccessWhenDuplicateMessageWasAlreadyPersistedDurably() throws Exception {
        resetAndMigrate();
        insertConversation(42L, 5L, 1_000L);

        MqConsumer mqConsumer = new MqConsumer(
            dataSource(),
            new MessageRepository(),
            new ConversationRepository(),
            mock(GroupMessageCache.class)
        );
        RocketMqPersistenceConsumer consumer = new RocketMqPersistenceConsumer(
            mock(DefaultMQPushConsumer.class),
            mqConsumer
        );
        MessageExt duplicate = message("1|42|6|11|private|100|100|200||2000|cGF5bG9hZA==");

        ConsumeOrderlyStatus status = consumer.consumeMessage(java.util.List.of(duplicate, duplicate), null);

        assertEquals(ConsumeOrderlyStatus.SUCCESS, status);
        assertEquals(1, messageCount(1L));
        assertEquals(new ConversationState(6L, 2_000L), conversationState(42L));
    }

    @Test
    void returnsSuspendWhenDurableConflictIsObserved() throws Exception {
        resetAndMigrate();
        insertConversation(42L, 5L, 1_000L);
        insertConversation(43L, 7L, 1_500L);

        MqConsumer mqConsumer = new MqConsumer(
            dataSource(),
            new MessageRepository(),
            new ConversationRepository(),
            mock(GroupMessageCache.class)
        );
        mqConsumer.persistMessage(new MessageRepository.PersistedMessage(
            1L,
            42L,
            6L,
            11L,
            "private",
            100L,
            100L,
            200L,
            null,
            2_000L,
            "cGF5bG9hZA=="
        ));
        RocketMqPersistenceConsumer consumer = new RocketMqPersistenceConsumer(
            mock(DefaultMQPushConsumer.class),
            mqConsumer
        );
        MessageExt conflict = message("1|43|8|12|private|100|100|200||3000|Y29uZmxpY3Q=");

        ConsumeOrderlyStatus status = consumer.consumeMessage(java.util.List.of(conflict), null);

        assertEquals(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT, status);
        assertEquals(1, messageCount(1L));
        assertEquals(new ConversationState(6L, 2_000L), conversationState(42L));
        assertEquals(new ConversationState(7L, 1_500L), conversationState(43L));
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

    private static int messageCount(long msgId) throws Exception {
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

    private static ConversationState conversationState(long conversationId) throws Exception {
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

    private static MessageExt message(String body) {
        MessageExt message = new MessageExt();
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private record ConversationState(long latestSeq, long latestMessageTime) {
    }
}
