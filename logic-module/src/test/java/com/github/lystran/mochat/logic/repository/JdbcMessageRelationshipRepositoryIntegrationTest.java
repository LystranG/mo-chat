package com.github.lystran.mochat.logic.repository;

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
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcMessageRelationshipRepositoryIntegrationTest {
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
        seedUser(33L, "carol", (byte) 3);
    }

    @Test
    void privateMessageStateTracksActiveBlockedAndMissingFriendship() throws SQLException {
        JdbcMessageRelationshipRepository repository = repository();
        seedFriendship(200L, 11L, 22L, "ok", null);

        assertEquals(
            MessageRelationshipRepository.PrivateMessageState.ACTIVE,
            repository.privateMessageState(200L, 11L, 22L)
        );

        updateFriendshipStatus(200L, "blocked", 0);
        assertEquals(
            MessageRelationshipRepository.PrivateMessageState.BLOCKED,
            repository.privateMessageState(200L, 11L, 22L)
        );

        assertEquals(
            MessageRelationshipRepository.PrivateMessageState.NOT_FRIEND,
            repository.privateMessageState(999L, 11L, 22L)
        );
    }

    @Test
    void groupQueriesDistinguishExistenceMembershipAndActiveMembers() throws SQLException {
        JdbcMessageRelationshipRepository repository = repository();
        seedGroup(300L, 11L, "dev-group");
        seedMembership(301L, 300L, 11L, "owner", "active");
        seedMembership(302L, 300L, 22L, "member", "active");
        seedMembership(303L, 300L, 33L, "member", "left");

        assertTrue(repository.groupExists(300L));
        assertFalse(repository.groupExists(999L));
        assertTrue(repository.isActiveGroupMember(300L, 11L));
        assertFalse(repository.isActiveGroupMember(300L, 33L));
        assertEquals(List.of(11L, 22L), repository.listActiveGroupMemberIds(300L));
    }

    private JdbcMessageRelationshipRepository repository() {
        return new JdbcMessageRelationshipRepository(dataSource());
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

    private void updateFriendshipStatus(long conversationId, String status, Integer blockedBy) throws SQLException {
        String sql = "UPDATE user_friendships SET status = ?, blocked_by = ? WHERE id = ?";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            if (blockedBy == null) {
                statement.setObject(2, null);
            } else {
                statement.setInt(2, blockedBy);
            }
            statement.setLong(3, conversationId);
            statement.executeUpdate();
        }
    }

    private void seedGroup(long groupId, long ownerUserId, String name) throws SQLException {
        String sql = "INSERT INTO groups (id, owner_uid, name) VALUES (?, ?, ?)";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, groupId);
            statement.setLong(2, ownerUserId);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    private void seedMembership(long membershipId, long groupId, long userId, String role, String status) throws SQLException {
        String sql = "INSERT INTO group_memberships (id, group_id, user_id, role, status) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, membershipId);
            statement.setLong(2, groupId);
            statement.setLong(3, userId);
            statement.setString(4, role);
            statement.setString(5, status);
            statement.executeUpdate();
        }
    }

    private byte[] key(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return key;
    }
}
