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
 * 基于 JDBC 的群管理实现。
 * 建群、入群审批、退群、踢人和解散群都在这里落库。
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
    // 群会话 id 直接复用 groupId，历史消息和群成员关系共用同一个 conversationId。
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
    // 同一个人再次进群时，直接把原有成员关系恢复成 active，而不是额外造一条新关系。
    private static final String UPSERT_MEMBER_MEMBERSHIP_SQL = """
        INSERT INTO group_memberships (id, group_id, user_id, role, status)
        VALUES (?, ?, ?, 'member', 'active')
        ON CONFLICT (group_id, user_id) DO UPDATE
        SET role = 'member', status = 'active', updated_at = now()
        """;

    private final DataSource dataSource;
    private final IdGenerator idGenerator;

    /**
     * 创建 JDBC 群仓储。
     */
    public JdbcGroupRepository(DataSource dataSource, IdGenerator idGenerator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /**
     * 创建一个新群，并在同一事务里补齐群主成员关系和群会话。
     */
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
                    // 建群人会被立即写成 owner + active，避免“群建好了但群主不在群里”的中间状态。
                    membershipStatement.setLong(1, membershipId);
                    membershipStatement.setLong(2, groupId);
                    membershipStatement.setLong(3, ownerUserId);
                    membershipStatement.executeUpdate();
                }
                try (PreparedStatement conversationStatement = connection.prepareStatement(INSERT_GROUP_CONVERSATION_SQL)) {
                    // 群会话和群本身共用 groupId，后续查历史时直接按这个 id 查。
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

    /**
     * 查询用户当前仍在其中的群。
     */
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

    /**
     * 让普通成员退出群聊。
     */
    @Override
    public void leaveGroup(long userId, long groupId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // 事务里读出来的当前成员关系，能避免读到旧状态后再被别人并发改掉。
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

    /**
     * 由群主把某个成员移出群聊。
     */
    @Override
    public void kickMember(long ownerUserId, long groupId, long memberUserId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireOwner(connection, ownerUserId, groupId);
                // 先锁住成员关系，再判断当前是不是还在群里、是不是群主本人。
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

    /**
     * 解散一个群，并把当前还活跃的成员关系一并收口。
     */
    @Override
    public void dissolveGroup(long ownerUserId, long groupId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireOwner(connection, ownerUserId, groupId);
                try (PreparedStatement memberships = connection.prepareStatement(DISSOLVE_ACTIVE_MEMBERSHIPS_SQL)) {
                    // 先把还在群里的成员统一改成 left，再删掉群本身。
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

    /**
     * 创建一条入群申请。
     */
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
            if (isDuplicatePendingJoinRequest(sqlException)) {
                throw new IllegalArgumentException("pending group join request already exists", sqlException);
            }
            throw new IllegalStateException("failed to create group join request", sqlException);
        }
    }

    /**
     * 列出某个群当前待处理的入群申请。
     */
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

    /**
     * 由群主处理入群申请。
     */
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
                if (decision == GroupJoinRequestDecision.ACCEPT) {
                    if (hasActiveMembership(connection, groupId, current.fromUserId())) {
                        // 这个人已经在群里了，当前申请和同组其他 pending 申请都直接收口成取消。
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
                    // 接受申请后，把这名用户恢复或写成 active member。
                    upsertMemberMembership(connection, groupId, current.fromUserId());
                    // 同一个人在同一群里不该留下多条待处理申请，这里顺手把兄弟 pending 申请一并取消。
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

    /**
     * 读取并锁住一条成员关系，返回它当前的角色和状态。
     */
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

    /**
     * 要求群必须存在。
     */
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

    /**
     * 要求当前操作者必须是群主。
     */
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

    /**
     * 判断某个用户当前是不是群里的活跃成员。
     */
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

    /**
     * 读取并锁住一条入群申请。
     */
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

    /**
     * 把入群申请状态更新成 accepted、rejected 或 cancelled。
     */
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

    /**
     * 取消同一个用户在同一个群里的其他待处理申请。
     */
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

    /**
     * 把成员关系写成 active member；如果以前离开过，会在这里恢复。
     */
    private void upsertMemberMembership(Connection connection, long groupId, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_MEMBER_MEMBERSHIP_SQL)) {
            statement.setLong(1, idGenerator.nextId());
            statement.setLong(2, groupId);
            statement.setLong(3, userId);
            statement.executeUpdate();
        }
    }

    /**
     * 把数据库结果整理成入群申请对象。
     */
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

    /**
     * 把可能为空的数据库数字值安全转成 Long。
     */
    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("unexpected numeric value: " + value);
    }

    /**
     * 把数据库时间戳转换成毫秒时间。
     */
    private static long toEpochMillis(Timestamp timestamp) {
        return Objects.requireNonNull(timestamp, "timestamp").getTime();
    }

    /**
     * 判断是否命中了“同一人对同一群已有待处理申请”的唯一约束。
     */
    private static boolean isDuplicatePendingJoinRequest(SQLException sqlException) {
        return "23505".equals(sqlException.getSQLState());
    }

    /**
     * 事务里读出来的当前这条成员关系的角色和状态。
     */
    private record MembershipSnapshot(String role, String status) {
    }
}
