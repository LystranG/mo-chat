package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.common.id.IdGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcUserRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    }

    @Test
    void createPersistsNewUserAndFindByUsernameLoadsItBack() {
        JdbcUserRepository repository = repository(() -> 101L);
        byte[] identityKey = key((byte) 7);

        UserProfile created = repository.create("alice", identityKey);
        Optional<UserProfile> loaded = repository.findByUsername("alice");

        assertTrue(loaded.isPresent());
        assertEquals(created.userId(), loaded.orElseThrow().userId());
        assertEquals("alice", loaded.orElseThrow().username());
        assertArrayEquals(identityKey, loaded.orElseThrow().identityPublicKey());
    }

    @Test
    void createWithExistingUsernameReturnsPersistedRowAndKeepsOriginalKey() throws SQLException {
        AtomicLong idSequence = new AtomicLong(200L);
        JdbcUserRepository repository = repository(idSequence::getAndIncrement);
        byte[] originalKey = key((byte) 3);
        byte[] competingKey = key((byte) 9);

        UserProfile first = repository.create("alice", originalKey);
        UserProfile second = repository.create("alice", competingKey);

        assertEquals(first.userId(), second.userId());
        assertArrayEquals(originalKey, second.identityPublicKey());
        assertEquals(1, userCount("alice"));
        assertArrayEquals(originalKey, storedPublicKey("alice"));
    }

    @Test
    void findByUsernameReturnsEmptyForMissingUser() {
        JdbcUserRepository repository = repository(() -> 301L);

        assertTrue(repository.findByUsername("missing").isEmpty());
    }

    private JdbcUserRepository repository(IdGenerator idGenerator) {
        return new JdbcUserRepository(dataSource(), idGenerator);
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private int userCount(String username) throws SQLException {
        String sql = """
            SELECT COUNT(*)
            FROM users
            WHERE username = ?
            """;

        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private byte[] storedPublicKey(String username) throws SQLException {
        String sql = """
            SELECT public_key
            FROM users
            WHERE username = ?
            """;

        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBytes(1);
            }
        }
    }

    private static byte[] key(byte value) {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = value;
        }
        return key;
    }
}
