package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于 JDBC 的消息发送关系校验实现。
 */
@Singleton
@Requires(beans = DataSource.class)
public final class JdbcMessageRelationshipRepository implements MessageRelationshipRepository {
    // 私聊会话 id 直接复用 friendship id，所以这里同时校验会话 id 和两端用户。
    private static final String FIND_PRIVATE_RELATIONSHIP_SQL = """
        SELECT status
        FROM user_friendships
        WHERE id = ?
          AND uid_1 = ?
          AND uid_2 = ?
        """;
    private static final String HAS_ACTIVE_GROUP_MEMBER_SQL = """
        SELECT EXISTS (
            SELECT 1
            FROM group_memberships
            WHERE group_id = ?
              AND user_id = ?
              AND status = 'active'
        )
        """;
    private static final String GROUP_EXISTS_SQL = """
        SELECT EXISTS (
            SELECT 1
            FROM groups
            WHERE id = ?
        )
        """;
    private static final String LIST_ACTIVE_GROUP_MEMBERS_SQL = """
        SELECT user_id
        FROM group_memberships
        WHERE group_id = ?
          AND status = 'active'
        ORDER BY user_id
        """;

    private final DataSource dataSource;

    /**
     * 创建 JDBC 消息关系仓储。
     */
    public JdbcMessageRelationshipRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 查询私聊关系当前是否允许发消息。
     */
    @Override
    public PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_PRIVATE_RELATIONSHIP_SQL)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, peerUidLow);
            statement.setLong(3, peerUidHigh);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return PrivateMessageState.NOT_FRIEND;
                }
                // user_friendships.status 目前只关心 ok 和 blocked，其他值统一按不可发处理。
                return switch (resultSet.getString(1)) {
                    case "ok" -> PrivateMessageState.ACTIVE;
                    case "blocked" -> PrivateMessageState.BLOCKED;
                    default -> PrivateMessageState.NOT_FRIEND;
                };
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to query private friendship state", sqlException);
        }
    }

    /**
     * 判断群是否存在。
     */
    @Override
    public boolean groupExists(long groupId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(GROUP_EXISTS_SQL)) {
            statement.setLong(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                return resultSet.getBoolean(1);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to verify group existence", sqlException);
        }
    }

    /**
     * 判断用户是不是群里的活跃成员。
     */
    @Override
    public boolean isActiveGroupMember(long groupId, long userId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(HAS_ACTIVE_GROUP_MEMBER_SQL)) {
            statement.setLong(1, groupId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                return resultSet.getBoolean(1);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to verify active group membership", sqlException);
        }
    }

    /**
     * 列出群里当前所有活跃成员 id。
     */
    @Override
    public List<Long> listActiveGroupMemberIds(long groupId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_ACTIVE_GROUP_MEMBERS_SQL)) {
            statement.setLong(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Long> memberIds = new ArrayList<>();
                while (resultSet.next()) {
                    memberIds.add(resultSet.getLong(1));
                }
                // 按 user_id 排序返回，方便上层拿到稳定顺序的成员列表。
                return memberIds;
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to list active group members", sqlException);
        }
    }
}
