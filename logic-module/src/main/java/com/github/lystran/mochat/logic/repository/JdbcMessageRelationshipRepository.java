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

    public JdbcMessageRelationshipRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

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
                return memberIds;
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to list active group members", sqlException);
        }
    }
}
