package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.common.id.IdGenerator;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

@Singleton
@Requires(beans = DataSource.class)
public final class JdbcUserRepository implements UserRepository {
    private static final String FIND_BY_USERNAME_SQL = """
        SELECT id, username, public_key
        FROM users
        WHERE username = ?
        """;
    private static final String INSERT_USER_SQL = """
        INSERT INTO users (id, username, public_key)
        VALUES (?, ?, ?)
        ON CONFLICT (username) DO NOTHING
        RETURNING id, username, public_key
        """;

    private final DataSource dataSource;
    private final IdGenerator idGenerator;

    public JdbcUserRepository(DataSource dataSource, IdGenerator idGenerator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    public Optional<UserProfile> findByUsername(String username) {
        try (Connection connection = dataSource.getConnection()) {
            return findByUsername(connection, username);
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to query user by username", sqlException);
        }
    }

    @Override
    public UserProfile create(String username, byte[] identityPublicKey) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_USER_SQL)) {
            statement.setLong(1, idGenerator.nextId());
            statement.setString(2, username);
            statement.setBytes(3, identityPublicKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return mapUserProfile(resultSet);
                }
            }

            return findByUsername(connection, username)
                .orElseThrow(() -> new IllegalStateException("failed to persist user: " + username));
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to persist user", sqlException);
        }
    }

    private Optional<UserProfile> findByUsername(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_USERNAME_SQL)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapUserProfile(resultSet));
            }
        }
    }

    private static UserProfile mapUserProfile(ResultSet resultSet) throws SQLException {
        return new UserProfile(
            resultSet.getLong(1),
            resultSet.getString(2),
            resultSet.getBytes(3)
        );
    }
}
