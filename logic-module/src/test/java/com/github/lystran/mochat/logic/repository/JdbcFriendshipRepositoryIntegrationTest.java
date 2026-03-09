package com.github.lystran.mochat.logic.repository;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcFriendshipRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void setUp() throws SQLException {
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

        seedUser(11L, "alice", (byte) 1);
        seedUser(22L, "bob", (byte) 2);
    }

    @Test
    void sendAndListFriendRequestsPreserveOpaqueSign() {
        JdbcFriendshipRepository repository = repository(new AtomicLong(900L)::getAndIncrement);

        FriendshipRepository.FriendRequestRow created = repository.createFriendRequest(11L, 22L, "opaque-base64-sign");
        List<FriendshipRepository.FriendRequestRow> sent = repository.listSentFriendRequests(11L);
        List<FriendshipRepository.FriendRequestRow> received = repository.listReceivedFriendRequests(22L);

        assertEquals("pending", created.status());
        assertEquals("opaque-base64-sign", created.sign());
        assertEquals(1, sent.size());
        assertEquals(created.requestId(), sent.get(0).requestId());
        assertEquals("opaque-base64-sign", sent.get(0).sign());
        assertEquals(1, received.size());
        assertEquals(created.requestId(), received.get(0).requestId());
        assertEquals("opaque-base64-sign", received.get(0).sign());
    }

    @Test
    void acceptingFriendRequestCreatesFriendshipAndConversation() throws SQLException {
        AtomicLong ids = new AtomicLong(1_000L);
        JdbcFriendshipRepository repository = repository(ids::getAndIncrement);
        FriendshipRepository.FriendRequestRow created = repository.createFriendRequest(11L, 22L, "opaque-base64-sign");

        FriendshipRepository.FriendRequestRow handled = repository.handleFriendRequest(
            created.requestId(),
            22L,
            FriendshipRepository.FriendRequestDecision.ACCEPT
        );

        assertEquals("accepted", handled.status());
        assertTrue(handled.handledAtEpochMillis().isPresent());

        Optional<FriendshipRowSnapshot> friendship = friendshipRow(11L, 22L);
        assertTrue(friendship.isPresent());
        assertEquals("ok", friendship.orElseThrow().status());
        assertNotNull(conversationType(friendship.orElseThrow().conversationId()));
        assertEquals(0, conversationType(friendship.orElseThrow().conversationId()));
    }

    @Test
    void rejectingFriendRequestDoesNotCreateFriendship() throws SQLException {
        AtomicLong ids = new AtomicLong(1_100L);
        JdbcFriendshipRepository repository = repository(ids::getAndIncrement);
        FriendshipRepository.FriendRequestRow created = repository.createFriendRequest(11L, 22L, "opaque-base64-sign");

        FriendshipRepository.FriendRequestRow handled = repository.handleFriendRequest(
            created.requestId(),
            22L,
            FriendshipRepository.FriendRequestDecision.REJECT
        );

        assertEquals("rejected", handled.status());
        assertTrue(handled.handledAtEpochMillis().isPresent());
        assertTrue(friendshipRow(11L, 22L).isEmpty());
    }

    @Test
    void blockingFriendshipPersistsBlockedByForBlockingSide() throws SQLException {
        JdbcFriendshipRepository repository = repository(new AtomicLong(1_200L)::getAndIncrement);
        seedFriendship(2_001L, 11L, 22L, "ok", null);

        repository.blockFriendship(22L, 11L);

        FriendshipRowSnapshot friendship = friendshipRow(11L, 22L).orElseThrow();
        assertEquals("blocked", friendship.status());
        assertEquals(1, friendship.blockedBy());
    }

    @Test
    void onlyBlockingSideCanUnblockFriendship() throws SQLException {
        JdbcFriendshipRepository repository = repository(new AtomicLong(1_300L)::getAndIncrement);
        seedFriendship(2_002L, 11L, 22L, "blocked", 1);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> repository.unblockFriend(11L, 22L)
        );

        assertTrue(exception.getMessage().contains("blocked"));
        repository.unblockFriend(22L, 11L);

        FriendshipRowSnapshot friendship = friendshipRow(11L, 22L).orElseThrow();
        assertEquals("ok", friendship.status());
        assertEquals(null, friendship.blockedBy());
    }

    @Test
    void deletingFriendshipRemovesRelationshipRow() throws SQLException {
        JdbcFriendshipRepository repository = repository(new AtomicLong(1_400L)::getAndIncrement);
        seedFriendship(2_003L, 11L, 22L, "ok", null);

        repository.deleteFriendship(11L, 22L);

        assertTrue(friendshipRow(11L, 22L).isEmpty());
    }

    private JdbcFriendshipRepository repository(IdGenerator idGenerator) {
        return new JdbcFriendshipRepository(dataSource(), idGenerator);
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private void seedUser(long userId, String username, byte keyByte) throws SQLException {
        String sql = "INSERT INTO users (id, username, public_key) VALUES (?, ?, ?)";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, username);
            statement.setBytes(3, key(keyByte));
            statement.executeUpdate();
        }
    }

    private Optional<FriendshipRowSnapshot> friendshipRow(long uid1, long uid2) throws SQLException {
        String sql = "SELECT id, status, blocked_by FROM user_friendships WHERE uid_1 = ? AND uid_2 = ?";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, uid1);
            statement.setLong(2, uid2);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new FriendshipRowSnapshot(
                    resultSet.getLong(1),
                    resultSet.getString(2),
                    (Integer) resultSet.getObject(3)
                ));
            }
        }
    }

    private void seedFriendship(long conversationId, long uid1, long uid2, String status, Integer blockedBy) throws SQLException {
        String friendshipSql = "INSERT INTO user_friendships (id, uid_1, uid_2, status, blocked_by) VALUES (?, ?, ?, ?, ?)";
        String conversationSql = "INSERT INTO conversations (id, type, latest_seq, latest_message_time, uid_1_seq, uid_2_seq) VALUES (?, 0, 0, 0, 0, 0)";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement friendshipStatement = connection.prepareStatement(friendshipSql);
             PreparedStatement conversationStatement = connection.prepareStatement(conversationSql)) {
            friendshipStatement.setLong(1, conversationId);
            friendshipStatement.setLong(2, uid1);
            friendshipStatement.setLong(3, uid2);
            friendshipStatement.setString(4, status);
            if (blockedBy == null) {
                friendshipStatement.setObject(5, null);
            } else {
                friendshipStatement.setInt(5, blockedBy);
            }
            friendshipStatement.executeUpdate();

            conversationStatement.setLong(1, conversationId);
            conversationStatement.executeUpdate();
        }
    }

    private Integer conversationType(long conversationId) throws SQLException {
        String sql = "SELECT type FROM conversations WHERE id = ?";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return resultSet.getInt(1);
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

    private record FriendshipRowSnapshot(long conversationId, String status, Integer blockedBy) {
    }


    @Test
    void duplicatePendingFriendRequestIsRejectedAsValidationError() {
        JdbcFriendshipRepository repository = repository(new AtomicLong(1_500L)::getAndIncrement);
        repository.createFriendRequest(11L, 22L, "opaque-base64-sign");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> repository.createFriendRequest(11L, 22L, "opaque-base64-sign")
        );

        assertTrue(exception.getMessage().contains("pending"));
    }

}
