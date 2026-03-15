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

/**
 * 基于 JDBC 处理群生命周期、成员状态和入群申请。
 */
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
    private static final String CANCEL_SIBLING_PENDING_JOIN_REQUESTS_SQL = """
        UPDATE group_join_requests
        SET status = 'cancelled', handled_by = ?, handled_at = now()
        WHERE group_id = ?
          AND from_uid = ?
          AND status = 'pending'
          AND id <> ?
        """;
    private static final String UPSERT_MEMBER_MEMBERSHIP_SQL = """
        INSERT INTO group_memberships (id, group_id, user_id, role, status)
        VALUES (?, ?, ?, 'member', 'active')
        ON CONFLICT (group_id, user_id) DO UPDATE
        SET role = 'member', status = 'active', updated_at = now()
        """;

    private final DataSource dataSource;
    private final IdGenerator idGenerator;

    // 注入 JDBC 数据源与 ID 生成器。
    public JdbcGroupRepository(DataSource dataSource, IdGenerator idGenerator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    // 创建群时同时写入群表、owner 成员关系和群会话三张事实表。
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
    // 查询用户当前加入的群列表。
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
    // 处理普通成员主动退群。
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
    // 处理 owner 踢人，并确保不能踢 owner 自己。
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
    // 解散群时先批量关闭活跃成员关系，再删除群主表记录。
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
    // 创建新的入群申请，并拒绝重复的 pending 申请。
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
            if (isDuplicatePendingJoinRequest(sqlException)) {
                throw new IllegalArgumentException("pending group join request already exists", sqlException);
            }
            throw new IllegalStateException("failed to create group join request", sqlException);
        }
    }

    @Override
    // 查询群 owner 当前可处理的入群申请列表。
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
    // 处理入群申请；如果同意，就把这个人重新设成群成员或补建成员记录。
    public GroupJoinRequestRow handleJoinRequest(long ownerUserId, long groupId, long requestId, GroupJoinRequestDecision decision) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireOwner(connection, ownerUserId, groupId);
                GroupJoinRequestRow current = loadJoinRequest(connection, groupId, requestId);
                if (!"pending".equals(current.status())) {
                    throw new IllegalArgumentException("group join request is not pending");
                }
                if (decision == GroupJoinRequestDecision.ACCEPT) {
                    if (hasActiveMembership(connection, groupId, current.fromUserId())) {
                        // 如果这个人其实已经在群里，就把申请改成“已取消”，避免看起来像“同意了但什么都没变”。
                        GroupJoinRequestRow cancelled = updateJoinRequest(connection, requestId, ownerUserId, "cancelled");
                        cancelSiblingPendingJoinRequests(connection, groupId, current.fromUserId(), requestId, ownerUserId);
                        connection.commit();
                        return cancelled;
                    }
                }
                GroupJoinRequestRow handled = updateJoinRequest(
                    connection,
                    requestId,
                    ownerUserId,
                    decision == GroupJoinRequestDecision.ACCEPT ? "accepted" : "rejected"
                );
                if (decision == GroupJoinRequestDecision.ACCEPT) {
                    upsertMemberMembership(connection, groupId, current.fromUserId());
                    cancelSiblingPendingJoinRequests(connection, groupId, current.fromUserId(), requestId, ownerUserId);
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

    // 把成员记录锁住，再读出他现在的角色和状态。
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

    // 校验群是否存在。
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

    // 校验请求方是否是群 owner。
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

    // 判断用户是否已经是群的活跃成员。
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

    // 把这条入群申请锁住，再读出它现在的状态。
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

    // 更新入群申请状态，并把更新后的结果读出来返回。
    private GroupJoinRequestRow updateJoinRequest(Connection connection, long requestId, long ownerUserId, String targetStatus)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_JOIN_REQUEST_SQL)) {
            statement.setString(1, targetStatus);
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

    // 把同一个人对同一个群的其他“待处理申请”一起取消，避免留下过期记录。
    private void cancelSiblingPendingJoinRequests(
        Connection connection,
        long groupId,
        long fromUserId,
        long handledRequestId,
        long ownerUserId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CANCEL_SIBLING_PENDING_JOIN_REQUESTS_SQL)) {
            statement.setLong(1, ownerUserId);
            statement.setLong(2, groupId);
            statement.setLong(3, fromUserId);
            statement.setLong(4, handledRequestId);
            statement.executeUpdate();
        }
    }

    // 同意入群时，把成员关系恢复成“还在群里”，或者直接补建一条成员记录。
    private void upsertMemberMembership(Connection connection, long groupId, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_MEMBER_MEMBERSHIP_SQL)) {
            statement.setLong(1, idGenerator.nextId());
            statement.setLong(2, groupId);
            statement.setLong(3, userId);
            statement.executeUpdate();
        }
    }

    // 把数据库查出来的一行组装成代码里的入群申请对象。
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

    // 兼容 JDBC 驱动可能返回的不同数字对象类型。
    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("unexpected numeric value: " + value);
    }

    // 将数据库时间戳转换为毫秒时间戳。
    private static long toEpochMillis(Timestamp timestamp) {
        return Objects.requireNonNull(timestamp, "timestamp").getTime();
    }

    // 识别“同一用户对同一群仍有 pending 申请”导致的唯一约束冲突。
    private static boolean isDuplicatePendingJoinRequest(SQLException sqlException) {
        return "23505".equals(sqlException.getSQLState());
    }

    /** 事务里读出来的群成员角色和状态。 */
    private record MembershipSnapshot(String role, String status) {
    }
}
