package com.github.lystran.mochat.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class MigrationSmokeTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrationCreatesConversationAndMessageTables() throws SQLException {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        )) {
            assertTrue(tableExists(connection, "conversations"));
            assertTrue(tableExists(connection, "messages"));
            assertTrue(columnExists(connection, "conversations", "latest_seq"));
            assertTrue(columnExists(connection, "conversations", "latest_message_time"));
            assertTrue(columnExists(connection, "conversations", "uid_1_seq"));
            assertTrue(columnExists(connection, "conversations", "uid_2_seq"));
            assertTrue(columnExists(connection, "messages", "conversation_id"));
            assertTrue(columnExists(connection, "messages", "seq"));
            assertTrue(columnExists(connection, "messages", "client_msg_id"));
            assertTrue(columnExists(connection, "messages", "payload_base64"));
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        String sql = """
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = ?
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        String sql = """
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
