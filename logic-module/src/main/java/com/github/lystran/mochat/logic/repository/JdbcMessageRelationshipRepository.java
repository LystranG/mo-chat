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
 * 基于 JDBC 查询私聊关系状态和群成员资格。
 */
@Singleton
@Requires(beans = DataSource.class)
public final class JdbcMessageRelationshipRepository implements MessageRelationshipRepository {
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
    private static final String LIST_ACTIVE_GROUP_MEMBERS_SQL = """
        SELECT user_id
        FROM group_memberships
        WHERE group_id = ?
          AND status = 'active'
        ORDER BY user_id
        """;

    private final DataSource dataSource;

    // 注入 JDBC 数据源。
    public JdbcMessageRelationshipRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    // 读取这场私聊现在到底是好友、已拉黑，还是根本不是好友。
    public PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_PRIVATE_RELATIONSHIP_SQL)) {
            statement.setLong(1, conversationId);
            statement.setLong(2, peerUidLow);
            statement.setLong(3, peerUidHigh);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    // 私聊会话在关系表里不存在时，统一按“不是好友”处理。
                    return PrivateMessageState.NOT_FRIEND;
                }
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

    @Override
    // 判断用户是否仍是群的活跃成员。
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

    @Override
    // 查出群里所有还在群里的成员 ID，群发时就按这份名单往外发。
    public List<Long> listActiveGroupMemberIds(long groupId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_ACTIVE_GROUP_MEMBERS_SQL)) {
            statement.setLong(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Long> memberIds = new ArrayList<>();
                while (resultSet.next()) {
                    memberIds.add(resultSet.getLong(1));
                }
                return memberIds;
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to list active group members", sqlException);
        }
    }
}
