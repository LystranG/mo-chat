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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcGroupRepositoryIntegrationTest {
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
    void createGroupCreatesOwnerMembershipAndConversation() throws SQLException {
        AtomicLong ids = new AtomicLong(7_000L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);

        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");

        assertEquals("dev-group", group.name());
        assertEquals(11L, group.ownerUserId());
        assertTrue(groupMembership(group.groupId(), 11L).isPresent());
        assertEquals(1, conversationType(group.groupId()));
    }

    @Test
    void listGroupsReturnsOnlyActiveMemberships() throws SQLException {
        AtomicLong ids = new AtomicLong(8_000L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow activeGroup = repository.createGroup(11L, "dev-group");
        seedGroup(8_100L, 22L, "archived-group");
        seedMembership(8_101L, 8_100L, 11L, "member", "left");

        List<GroupRepository.GroupRow> groups = repository.listGroups(11L);

        assertEquals(1, groups.size());
        assertEquals(activeGroup.groupId(), groups.get(0).groupId());
        assertEquals("dev-group", groups.get(0).name());
    }

    @Test
    void leavingGroupMarksMembershipLeftAndHidesItFromList() throws SQLException {
        AtomicLong ids = new AtomicLong(8_200L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(22L, "ops-group");
        seedMembership(8_299L, group.groupId(), 11L, "member", "active");

        repository.leaveGroup(11L, group.groupId());

        assertEquals(Optional.empty(), groupMembership(group.groupId(), 11L));
        assertEquals("left", membershipStatus(group.groupId(), 11L));
        assertTrue(repository.listGroups(11L).isEmpty());
    }

    @Test
    void ownerCannotLeaveGroup() {
        AtomicLong ids = new AtomicLong(8_300L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> repository.leaveGroup(11L, group.groupId())
        );

        assertTrue(exception.getMessage().contains("owner"));
    }

    @Test
    void ownerCanKickActiveMember() throws SQLException {
        AtomicLong ids = new AtomicLong(8_400L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");
        seedMembership(8_499L, group.groupId(), 22L, "member", "active");

        repository.kickMember(11L, group.groupId(), 22L);

        assertEquals(Optional.empty(), groupMembership(group.groupId(), 22L));
        assertEquals("kicked", membershipStatus(group.groupId(), 22L));
        assertTrue(repository.listGroups(22L).isEmpty());
    }

    @Test
    void ownerCannotKickSelf() {
        AtomicLong ids = new AtomicLong(8_500L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> repository.kickMember(11L, group.groupId(), 11L)
        );

        assertTrue(exception.getMessage().contains("owner"));
    }

    @Test
    void ownerCanDissolveGroupAndInvalidateMemberships() throws SQLException {
        AtomicLong ids = new AtomicLong(8_600L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");
        seedMembership(8_699L, group.groupId(), 22L, "member", "active");

        repository.dissolveGroup(11L, group.groupId());

        assertFalse(groupExists(group.groupId()));
        assertEquals("left", membershipStatus(group.groupId(), 11L));
        assertEquals("left", membershipStatus(group.groupId(), 22L));
        assertTrue(repository.listGroups(11L).isEmpty());
        assertTrue(repository.listGroups(22L).isEmpty());
    }

    @Test
    void sendAndListJoinRequestsPreserveOpaqueSign() {
        AtomicLong ids = new AtomicLong(9_000L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");

        GroupRepository.GroupJoinRequestRow created = repository.createJoinRequest(22L, group.groupId(), "opaque-sign");
        List<GroupRepository.GroupJoinRequestRow> requests = repository.listJoinRequests(11L, group.groupId());

        assertEquals("pending", created.status());
        assertEquals("opaque-sign", created.sign());
        assertEquals(1, requests.size());
        assertEquals(created.requestId(), requests.get(0).requestId());
        assertEquals("opaque-sign", requests.get(0).sign());
    }

    @Test
    void acceptingJoinRequestCreatesActiveMembership() throws SQLException {
        AtomicLong ids = new AtomicLong(9_100L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");
        GroupRepository.GroupJoinRequestRow created = repository.createJoinRequest(22L, group.groupId(), "opaque-sign");

        GroupRepository.GroupJoinRequestRow handled = repository.handleJoinRequest(
            11L,
            group.groupId(),
            created.requestId(),
            GroupRepository.GroupJoinRequestDecision.ACCEPT
        );

        assertEquals("accepted", handled.status());
        assertEquals(11L, handled.handledByUserId());
        assertEquals(Optional.of("member"), groupMembership(group.groupId(), 22L));
        assertEquals("active", membershipStatus(group.groupId(), 22L));
    }

    @Test
    void rejectingJoinRequestDoesNotCreateMembership() throws SQLException {
        AtomicLong ids = new AtomicLong(9_200L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");
        GroupRepository.GroupJoinRequestRow created = repository.createJoinRequest(22L, group.groupId(), "opaque-sign");

        GroupRepository.GroupJoinRequestRow handled = repository.handleJoinRequest(
            11L,
            group.groupId(),
            created.requestId(),
            GroupRepository.GroupJoinRequestDecision.REJECT
        );

        assertEquals("rejected", handled.status());
        assertEquals(11L, handled.handledByUserId());
        assertEquals(Optional.empty(), groupMembership(group.groupId(), 22L));
        assertEquals("rejected", joinRequestStatus(created.requestId()));
    }

    @Test
    void listJoinRequestsReturnsOnlyPendingRequests() {
        AtomicLong ids = new AtomicLong(9_250L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");
        GroupRepository.GroupJoinRequestRow pending = repository.createJoinRequest(22L, group.groupId(), "pending-sign");
        GroupRepository.GroupJoinRequestRow handled = repository.createJoinRequest(33L, group.groupId(), "handled-sign");

        repository.handleJoinRequest(11L, group.groupId(), handled.requestId(), GroupRepository.GroupJoinRequestDecision.REJECT);
        List<GroupRepository.GroupJoinRequestRow> requests = repository.listJoinRequests(11L, group.groupId());

        assertEquals(1, requests.size());
        assertEquals(pending.requestId(), requests.get(0).requestId());
        assertEquals("pending", requests.get(0).status());
    }

    @Test
    void kickingMemberMarksMembershipKickedAndHidesItFromList() throws SQLException {
        AtomicLong ids = new AtomicLong(9_300L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");
        seedMembership(9_399L, group.groupId(), 22L, "member", "active");

        repository.kickMember(11L, group.groupId(), 22L);

        assertEquals(Optional.empty(), groupMembership(group.groupId(), 22L));
        assertEquals("kicked", membershipStatus(group.groupId(), 22L));
        assertTrue(repository.listGroups(22L).isEmpty());
    }

    @Test
    void onlyOwnerCanKickMember() throws SQLException {
        AtomicLong ids = new AtomicLong(9_400L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");
        seedMembership(9_499L, group.groupId(), 22L, "member", "active");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> repository.kickMember(22L, group.groupId(), 22L)
        );

        assertTrue(exception.getMessage().contains("owner"));
    }

    @Test
    void dissolvingGroupInvalidatesMembershipsAndRemovesGroup() throws SQLException {
        AtomicLong ids = new AtomicLong(9_500L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");
        seedMembership(9_599L, group.groupId(), 22L, "member", "active");

        repository.dissolveGroup(11L, group.groupId());

        assertEquals("left", membershipStatus(group.groupId(), 11L));
        assertEquals("left", membershipStatus(group.groupId(), 22L));
        assertTrue(repository.listGroups(11L).isEmpty());
        assertTrue(repository.listGroups(22L).isEmpty());
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> repository.createJoinRequest(33L, group.groupId(), "opaque-sign")
        );
        assertEquals("group not found", exception.getMessage());
    }

    @Test
    void onlyOwnerCanDissolveGroup() {
        AtomicLong ids = new AtomicLong(9_600L);
        JdbcGroupRepository repository = repository(ids::getAndIncrement);
        GroupRepository.GroupRow group = repository.createGroup(11L, "dev-group");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> repository.dissolveGroup(22L, group.groupId())
        );

        assertTrue(exception.getMessage().contains("owner"));
    }

    private JdbcGroupRepository repository(IdGenerator idGenerator) {
        return new JdbcGroupRepository(dataSource(), idGenerator);
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private Optional<String> groupMembership(long groupId, long userId) throws SQLException {
        String sql = "SELECT role FROM group_memberships WHERE group_id = ? AND user_id = ? AND status = 'active'";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, groupId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(resultSet.getString(1));
            }
        }
    }

    private String membershipStatus(long groupId, long userId) throws SQLException {
        String sql = "SELECT status FROM group_memberships WHERE group_id = ? AND user_id = ?";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, groupId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private String joinRequestStatus(long requestId) throws SQLException {
        String sql = "SELECT status FROM group_join_requests WHERE id = ?";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, requestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private boolean groupExists(long groupId) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM groups WHERE id = ?)";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private int conversationType(long conversationId) throws SQLException {
        String sql = "SELECT type FROM conversations WHERE id = ?";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
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
}
