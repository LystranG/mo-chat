package com.github.lystran.mochat.logic.repository;

import com.github.lystran.mochat.common.id.IdGenerator;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Singleton
@Requires(beans = DataSource.class)
public final class JdbcGroupRepository implements GroupRepository {
    private static final String INSERT_GROUP_SQL = """
        INSERT INTO groups (id, owner_uid, name)
        VALUES (?, ?, ?)
        """;
    private static final String INSERT_GROUP_MEMBERSHIP_SQL = """
        INSERT INTO group_memberships (id, group_id, user_id, role, status)
        VALUES (?, ?, ?, 'owner', 'active')
        """;
    private static final String INSERT_GROUP_CONVERSATION_SQL = """
        INSERT INTO conversations (id, type, latest_seq, latest_message_time, uid_1_seq, uid_2_seq)
        VALUES (?, 1, 0, 0, 0, 0)
        """;
    private static final String LIST_GROUPS_SQL = """
        SELECT g.id, g.name, g.owner_uid
        FROM groups g
        JOIN group_memberships gm ON gm.group_id = g.id
        WHERE gm.user_id = ?
          AND gm.status = 'active'
        ORDER BY g.id ASC
        """;
    private static final String LOAD_MEMBERSHIP_SQL = """
        SELECT role, status
        FROM group_memberships
        WHERE group_id = ?
          AND user_id = ?
        FOR UPDATE
        """;
    private static final String LEAVE_GROUP_SQL = """
        UPDATE group_memberships
        SET status = 'left', updated_at = now()
        WHERE group_id = ?
          AND user_id = ?
        """;
    private static final String KICK_MEMBER_SQL = """
        UPDATE group_memberships
        SET status = 'kicked', updated_at = now()
        WHERE group_id = ?
          AND user_id = ?
        """;
    private static final String DISSOLVE_ACTIVE_MEMBERSHIPS_SQL = """
        UPDATE group_memberships
        SET status = 'left', updated_at = now()
        WHERE group_id = ?
          AND status = 'active'
        """;
    private static final String DELETE_GROUP_SQL = """
        DELETE FROM groups
        WHERE id = ?
        """;
    private static final String LOAD_GROUP_SQL = """
        SELECT owner_uid
        FROM groups
        WHERE id = ?
        """;
    private static final String HAS_ACTIVE_MEMBERSHIP_SQL = """
        SELECT EXISTS (
            SELECT 1
            FROM group_memberships
            WHERE group_id = ?
              AND user_id = ?
              AND status = 'active'
        )
        """;
    private static final String INSERT_JOIN_REQUEST_SQL = """
        INSERT INTO group_join_requests (id, group_id, from_uid, sign, status)
        VALUES (?, ?, ?, ?, 'pending')
        RETURNING id, group_id, from_uid, sign, status, created_at, handled_by, handled_at
        """;
    private static final String LIST_JOIN_REQUESTS_SQL = """
        SELECT id, group_id, from_uid, sign, status, created_at, handled_by, handled_at
        FROM group_join_requests
        WHERE group_id = ?
          AND status = 'pending'
        ORDER BY created_at DESC, id DESC
        """;
    private static final String LOAD_JOIN_REQUEST_SQL = """
        SELECT id, group_id, from_uid, sign, status, created_at, handled_by, handled_at
        FROM group_join_requests
        WHERE id = ?
          AND group_id = ?
        FOR UPDATE
        """;
    private static final String UPDATE_JOIN_REQUEST_SQL = """
        UPDATE group_join_requests
        SET status = ?, handled_by = ?, handled_at = now()
        WHERE id = ?
        RETURNING id, group_id, from_uid, sign, status, created_at, handled_by, handled_at
        """;
    private static final String UPSERT_MEMBER_MEMBERSHIP_SQL = """
        INSERT INTO group_memberships (id, group_id, user_id, role, status)
        VALUES (?, ?, ?, 'member', 'active')
        ON CONFLICT (group_id, user_id) DO UPDATE
        SET role = 'member', status = 'active', updated_at = now()
        """;

    private final DataSource dataSource;
    private final IdGenerator idGenerator;

    public JdbcGroupRepository(DataSource dataSource, IdGenerator idGenerator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    public GroupRow createGroup(long ownerUserId, String name) {
        long groupId = idGenerator.nextId();
        long membershipId = idGenerator.nextId();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement groupStatement = connection.prepareStatement(INSERT_GROUP_SQL)) {
                    groupStatement.setLong(1, groupId);
                    groupStatement.setLong(2, ownerUserId);
                    groupStatement.setString(3, name);
                    groupStatement.executeUpdate();
                }
                try (PreparedStatement membershipStatement = connection.prepareStatement(INSERT_GROUP_MEMBERSHIP_SQL)) {
                    membershipStatement.setLong(1, membershipId);
                    membershipStatement.setLong(2, groupId);
                    membershipStatement.setLong(3, ownerUserId);
                    membershipStatement.executeUpdate();
                }
                try (PreparedStatement conversationStatement = connection.prepareStatement(INSERT_GROUP_CONVERSATION_SQL)) {
                    conversationStatement.setLong(1, groupId);
                    conversationStatement.executeUpdate();
                }
                connection.commit();
                return new GroupRow(groupId, name, ownerUserId);
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to create group", sqlException);
        }
    }

    @Override
    public List<GroupRow> listGroups(long userId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_GROUPS_SQL)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<GroupRow> groups = new ArrayList<>();
                while (resultSet.next()) {
                    groups.add(new GroupRow(resultSet.getLong(1), resultSet.getString(2), resultSet.getLong(3)));
                }
                return List.copyOf(groups);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to list groups", sqlException);
        }
    }

    @Override
    public void leaveGroup(long userId, long groupId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MembershipSnapshot membership = loadMembership(connection, userId, groupId);
                if (!"active".equals(membership.status())) {
                    throw new IllegalArgumentException("group membership is not active");
                }
                if ("owner".equals(membership.role())) {
                    throw new IllegalArgumentException("group owner cannot leave group");
                }
                try (PreparedStatement statement = connection.prepareStatement(LEAVE_GROUP_SQL)) {
                    statement.setLong(1, groupId);
                    statement.setLong(2, userId);
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalArgumentException("group membership not found");
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to leave group", sqlException);
        }
    }

    @Override
    public void kickMember(long ownerUserId, long groupId, long memberUserId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireOwner(connection, ownerUserId, groupId);
                MembershipSnapshot membership = loadMembership(connection, memberUserId, groupId);
                if (!"active".equals(membership.status())) {
                    throw new IllegalArgumentException("group membership is not active");
                }
                if ("owner".equals(membership.role())) {
                    throw new IllegalArgumentException("group owner cannot be kicked");
                }
                try (PreparedStatement statement = connection.prepareStatement(KICK_MEMBER_SQL)) {
                    statement.setLong(1, groupId);
                    statement.setLong(2, memberUserId);
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalArgumentException("group membership not found");
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to kick group member", sqlException);
        }
    }

    @Override
    public void dissolveGroup(long ownerUserId, long groupId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireOwner(connection, ownerUserId, groupId);
                try (PreparedStatement memberships = connection.prepareStatement(DISSOLVE_ACTIVE_MEMBERSHIPS_SQL)) {
                    memberships.setLong(1, groupId);
                    memberships.executeUpdate();
                }
                try (PreparedStatement groupStatement = connection.prepareStatement(DELETE_GROUP_SQL)) {
                    groupStatement.setLong(1, groupId);
                    if (groupStatement.executeUpdate() == 0) {
                        throw new IllegalArgumentException("group not found");
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to dissolve group", sqlException);
        }
    }

    @Override
    public GroupJoinRequestRow createJoinRequest(long requesterUserId, long groupId, String sign) {
        try (Connection connection = dataSource.getConnection()) {
            requireGroupExists(connection, groupId);
            if (hasActiveMembership(connection, groupId, requesterUserId)) {
                throw new IllegalArgumentException("group member already active");
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_JOIN_REQUEST_SQL)) {
                statement.setLong(1, idGenerator.nextId());
                statement.setLong(2, groupId);
                statement.setLong(3, requesterUserId);
                statement.setString(4, sign);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("failed to create group join request");
                    }
                    return mapJoinRequest(resultSet);
                }
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to create group join request", sqlException);
        }
    }

    @Override
    public List<GroupJoinRequestRow> listJoinRequests(long ownerUserId, long groupId) {
        try (Connection connection = dataSource.getConnection()) {
            requireOwner(connection, ownerUserId, groupId);
            try (PreparedStatement statement = connection.prepareStatement(LIST_JOIN_REQUESTS_SQL)) {
                statement.setLong(1, groupId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<GroupJoinRequestRow> requests = new ArrayList<>();
                    while (resultSet.next()) {
                        requests.add(mapJoinRequest(resultSet));
                    }
                    return List.copyOf(requests);
                }
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to list group join requests", sqlException);
        }
    }

    @Override
    public GroupJoinRequestRow handleJoinRequest(long ownerUserId, long groupId, long requestId, GroupJoinRequestDecision decision) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireOwner(connection, ownerUserId, groupId);
                GroupJoinRequestRow current = loadJoinRequest(connection, groupId, requestId);
                if (!"pending".equals(current.status())) {
                    throw new IllegalArgumentException("group join request is not pending");
                }
                GroupJoinRequestRow handled = updateJoinRequest(connection, requestId, ownerUserId, decision);
                if (decision == GroupJoinRequestDecision.ACCEPT) {
                    upsertMemberMembership(connection, groupId, current.fromUserId());
                }
                connection.commit();
                return handled;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to handle group join request", sqlException);
        }
    }

    private MembershipSnapshot loadMembership(Connection connection, long userId, long groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOAD_MEMBERSHIP_SQL)) {
            statement.setLong(1, groupId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("group membership not found");
                }
                return new MembershipSnapshot(resultSet.getString(1), resultSet.getString(2));
            }
        }
    }

    private void requireGroupExists(Connection connection, long groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOAD_GROUP_SQL)) {
            statement.setLong(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("group not found");
                }
            }
        }
    }

    private void requireOwner(Connection connection, long ownerUserId, long groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOAD_GROUP_SQL)) {
            statement.setLong(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("group not found");
                }
                if (resultSet.getLong(1) != ownerUserId) {
                    throw new IllegalArgumentException("group owner required");
                }
            }
        }
    }

    private boolean hasActiveMembership(Connection connection, long groupId, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(HAS_ACTIVE_MEMBERSHIP_SQL)) {
            statement.setLong(1, groupId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private GroupJoinRequestRow loadJoinRequest(Connection connection, long groupId, long requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOAD_JOIN_REQUEST_SQL)) {
            statement.setLong(1, requestId);
            statement.setLong(2, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("group join request not found");
                }
                return mapJoinRequest(resultSet);
            }
        }
    }

    private GroupJoinRequestRow updateJoinRequest(
        Connection connection,
        long requestId,
        long ownerUserId,
        GroupJoinRequestDecision decision
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_JOIN_REQUEST_SQL)) {
            statement.setString(1, decision == GroupJoinRequestDecision.ACCEPT ? "accepted" : "rejected");
            statement.setLong(2, ownerUserId);
            statement.setLong(3, requestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("failed to update group join request");
                }
                return mapJoinRequest(resultSet);
            }
        }
    }

    private void upsertMemberMembership(Connection connection, long groupId, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_MEMBER_MEMBERSHIP_SQL)) {
            statement.setLong(1, idGenerator.nextId());
            statement.setLong(2, groupId);
            statement.setLong(3, userId);
            statement.executeUpdate();
        }
    }

    private static GroupJoinRequestRow mapJoinRequest(ResultSet resultSet) throws SQLException {
        return new GroupJoinRequestRow(
            resultSet.getLong(1),
            resultSet.getLong(2),
            resultSet.getLong(3),
            resultSet.getString(4),
            resultSet.getString(5),
            toEpochMillis(resultSet.getTimestamp(6)),
            nullableLong(resultSet.getObject(7)),
            resultSet.getTimestamp(8) == null ? null : toEpochMillis(resultSet.getTimestamp(8))
        );
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("unexpected numeric value: " + value);
    }

    private static long toEpochMillis(Timestamp timestamp) {
        return Objects.requireNonNull(timestamp, "timestamp").getTime();
    }

    private record MembershipSnapshot(String role, String status) {
    }
}
