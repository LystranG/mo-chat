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
import java.util.Optional;

@Singleton
@Requires(beans = DataSource.class)
public final class JdbcFriendshipRepository implements FriendshipRepository {
    private static final String INSERT_FRIEND_REQUEST_SQL = """
        INSERT INTO friend_requests (id, from_uid, to_uid, sign, status)
        VALUES (?, ?, ?, ?, 'pending')
        RETURNING id, from_uid, to_uid, sign, status, created_at, handled_at
        """;
    private static final String LIST_SENT_REQUESTS_SQL = """
        SELECT id, from_uid, to_uid, sign, status, created_at, handled_at
        FROM friend_requests
        WHERE from_uid = ?
        ORDER BY created_at DESC, id DESC
        """;
    private static final String LIST_RECEIVED_REQUESTS_SQL = """
        SELECT id, from_uid, to_uid, sign, status, created_at, handled_at
        FROM friend_requests
        WHERE to_uid = ?
        ORDER BY created_at DESC, id DESC
        """;
    private static final String LOAD_FRIEND_REQUEST_FOR_HANDLE_SQL = """
        SELECT id, from_uid, to_uid, sign, status, created_at, handled_at
        FROM friend_requests
        WHERE id = ?
          AND to_uid = ?
        FOR UPDATE
        """;
    private static final String UPDATE_FRIEND_REQUEST_STATUS_SQL = """
        UPDATE friend_requests
        SET status = ?, handled_at = now()
        WHERE id = ?
        RETURNING id, from_uid, to_uid, sign, status, created_at, handled_at
        """;
    private static final String UPSERT_FRIENDSHIP_SQL = """
        INSERT INTO user_friendships (id, uid_1, uid_2, status, blocked_by)
        VALUES (?, ?, ?, 'ok', NULL)
        ON CONFLICT (uid_1, uid_2) DO UPDATE
        SET status = 'ok', blocked_by = NULL, updated_at = now()
        RETURNING id
        """;
    private static final String INSERT_PRIVATE_CONVERSATION_SQL = """
        INSERT INTO conversations (id, type, latest_seq, latest_message_time, uid_1_seq, uid_2_seq)
        VALUES (?, 0, 0, 0, 0, 0)
        ON CONFLICT (id) DO NOTHING
        """;
    private static final String DELETE_FRIENDSHIP_SQL = """
        DELETE FROM user_friendships
        WHERE uid_1 = ?
          AND uid_2 = ?
        """;
    private static final String BLOCK_FRIENDSHIP_SQL = """
        UPDATE user_friendships
        SET status = 'blocked', blocked_by = ?, updated_at = now()
        WHERE uid_1 = ?
          AND uid_2 = ?
        """;
    private static final String UNBLOCK_FRIENDSHIP_SQL = """
        UPDATE user_friendships
        SET status = 'ok', blocked_by = NULL, updated_at = now()
        WHERE uid_1 = ?
          AND uid_2 = ?
          AND status = 'blocked'
          AND blocked_by = ?
        """;

    private final DataSource dataSource;
    private final IdGenerator idGenerator;

    public JdbcFriendshipRepository(DataSource dataSource, IdGenerator idGenerator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    public FriendRequestRow createFriendRequest(long fromUserId, long toUserId, String sign) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_FRIEND_REQUEST_SQL)) {
            statement.setLong(1, idGenerator.nextId());
            statement.setLong(2, fromUserId);
            statement.setLong(3, toUserId);
            statement.setString(4, sign);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("failed to persist friend request");
                }
                return mapFriendRequest(resultSet);
            }
        } catch (SQLException sqlException) {
            if (isDuplicatePendingFriendRequest(sqlException)) {
                throw new IllegalArgumentException("pending friend request already exists", sqlException);
            }
            throw new IllegalStateException("failed to persist friend request", sqlException);
        }
    }

    @Override
    public List<FriendRequestRow> listSentFriendRequests(long userId) {
        return listFriendRequests(userId, LIST_SENT_REQUESTS_SQL, "failed to list sent friend requests");
    }

    @Override
    public List<FriendRequestRow> listReceivedFriendRequests(long userId) {
        return listFriendRequests(userId, LIST_RECEIVED_REQUESTS_SQL, "failed to list received friend requests");
    }

    @Override
    public FriendRequestRow handleFriendRequest(long requestId, long handlerUserId, FriendRequestDecision decision) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                FriendRequestRow current = loadFriendRequestForHandle(connection, requestId, handlerUserId)
                    .orElseThrow(() -> new IllegalArgumentException("friend request not found"));
                if (!"pending".equals(current.status())) {
                    throw new IllegalArgumentException("friend request is not pending");
                }

                String targetStatus = switch (decision) {
                    case ACCEPT -> "accepted";
                    case REJECT -> "rejected";
                };
                FriendRequestRow handled = updateFriendRequestStatus(connection, requestId, targetStatus);
                if (decision == FriendRequestDecision.ACCEPT) {
                    ensurePrivateConversation(connection, current.fromUserId(), current.toUserId());
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
            throw new IllegalStateException("failed to handle friend request", sqlException);
        }
    }

    @Override
    public void deleteFriendship(long userId, long friendUserId) {
        long uid1 = Math.min(userId, friendUserId);
        long uid2 = Math.max(userId, friendUserId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_FRIENDSHIP_SQL)) {
            statement.setLong(1, uid1);
            statement.setLong(2, uid2);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("friendship not found");
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to delete friendship", sqlException);
        }
    }

    @Override
    public void blockFriendship(long userId, long friendUserId) {
        long uid1 = Math.min(userId, friendUserId);
        long uid2 = Math.max(userId, friendUserId);
        int blockedBy = userId == uid1 ? 0 : 1;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(BLOCK_FRIENDSHIP_SQL)) {
            statement.setInt(1, blockedBy);
            statement.setLong(2, uid1);
            statement.setLong(3, uid2);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("friendship not found");
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to block friendship", sqlException);
        }
    }

    @Override
    public void unblockFriend(long userId, long friendUserId) {
        long uid1 = Math.min(userId, friendUserId);
        long uid2 = Math.max(userId, friendUserId);
        int blockedBy = userId == uid1 ? 0 : 1;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UNBLOCK_FRIENDSHIP_SQL)) {
            statement.setLong(1, uid1);
            statement.setLong(2, uid2);
            statement.setInt(3, blockedBy);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("friendship is not blocked by requester");
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to unblock friendship", sqlException);
        }
    }

    private List<FriendRequestRow> listFriendRequests(long userId, String sql, String errorMessage) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FriendRequestRow> requests = new ArrayList<>();
                while (resultSet.next()) {
                    requests.add(mapFriendRequest(resultSet));
                }
                return List.copyOf(requests);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException(errorMessage, sqlException);
        }
    }

    private Optional<FriendRequestRow> loadFriendRequestForHandle(Connection connection, long requestId, long handlerUserId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOAD_FRIEND_REQUEST_FOR_HANDLE_SQL)) {
            statement.setLong(1, requestId);
            statement.setLong(2, handlerUserId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapFriendRequest(resultSet));
            }
        }
    }

    private FriendRequestRow updateFriendRequestStatus(Connection connection, long requestId, String targetStatus) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_FRIEND_REQUEST_STATUS_SQL)) {
            statement.setString(1, targetStatus);
            statement.setLong(2, requestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("failed to update friend request status");
                }
                return mapFriendRequest(resultSet);
            }
        }
    }

    private void ensurePrivateConversation(Connection connection, long userIdA, long userIdB) throws SQLException {
        long uid1 = Math.min(userIdA, userIdB);
        long uid2 = Math.max(userIdA, userIdB);
        long friendshipId;
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_FRIENDSHIP_SQL)) {
            statement.setLong(1, idGenerator.nextId());
            statement.setLong(2, uid1);
            statement.setLong(3, uid2);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("failed to create friendship row");
                }
                friendshipId = resultSet.getLong(1);
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(INSERT_PRIVATE_CONVERSATION_SQL)) {
            statement.setLong(1, friendshipId);
            statement.executeUpdate();
        }
    }

    private static FriendRequestRow mapFriendRequest(ResultSet resultSet) throws SQLException {
        return new FriendRequestRow(
            resultSet.getLong(1),
            resultSet.getLong(2),
            resultSet.getLong(3),
            resultSet.getString(4),
            resultSet.getString(5),
            toEpochMillis(resultSet.getTimestamp(6)),
            Optional.ofNullable(resultSet.getTimestamp(7)).map(Timestamp::getTime)
        );
    }

    private static boolean isDuplicatePendingFriendRequest(SQLException sqlException) {
        return "23505".equals(sqlException.getSQLState());
    }

    private static long toEpochMillis(Timestamp timestamp) {
        if (timestamp == null) {
            throw new IllegalStateException("timestamp must not be null");
        }
        return timestamp.getTime();
    }
}
