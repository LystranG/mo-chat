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
public final class JdbcFriendListRepository implements FriendListRepository {
    private static final String LIST_ACTIVE_FRIENDS_SQL = """
        SELECT f.id,
               CASE WHEN f.uid_1 = ? THEN u2.id ELSE u1.id END AS friend_user_id,
               CASE WHEN f.uid_1 = ? THEN u2.username ELSE u1.username END AS friend_username
        FROM user_friendships f
        JOIN users u1 ON u1.id = f.uid_1
        JOIN users u2 ON u2.id = f.uid_2
        WHERE f.status = 'ok'
          AND (? = f.uid_1 OR ? = f.uid_2)
        ORDER BY f.id ASC
        """;

    private final DataSource dataSource;

    public JdbcFriendListRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<FriendRow> listActiveFriends(long userId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_ACTIVE_FRIENDS_SQL)) {
            statement.setLong(1, userId);
            statement.setLong(2, userId);
            statement.setLong(3, userId);
            statement.setLong(4, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FriendRow> friends = new ArrayList<>();
                while (resultSet.next()) {
                    friends.add(new FriendRow(resultSet.getLong(1), resultSet.getLong(2), resultSet.getString(3)));
                }
                return List.copyOf(friends);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to list active friends", sqlException);
        }
    }
}
