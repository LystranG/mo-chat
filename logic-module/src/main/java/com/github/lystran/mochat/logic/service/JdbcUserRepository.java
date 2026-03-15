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

/**
 * 基于 JDBC 的用户仓储，负责按用户名读取或落库用户资料。
 */
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

    /**
     * 创建 JDBC 用户仓储。
     */
    public JdbcUserRepository(DataSource dataSource, IdGenerator idGenerator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /**
     * 按用户名查找用户资料。
     */
    @Override
    public Optional<UserProfile> findByUsername(String username) {
        try (Connection connection = dataSource.getConnection()) {
            return findByUsername(connection, username);
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to query user by username", sqlException);
        }
    }

    /**
     * 创建用户；如果并发下已有同名用户落库，就回头把已有记录读出来。
     */
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

            // ON CONFLICT DO NOTHING 说明有人并发创建了同名用户，这里回查现有记录即可。
            return findByUsername(connection, username)
                .orElseThrow(() -> new IllegalStateException("failed to persist user: " + username));
        } catch (SQLException sqlException) {
            throw new IllegalStateException("failed to persist user", sqlException);
        }
    }

    /**
     * 在同一条数据库连接里按用户名查询用户，方便复用到并发创建回查场景。
     */
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

    /**
     * 把数据库结果整理成用户资料对象。
     */
    private static UserProfile mapUserProfile(ResultSet resultSet) throws SQLException {
        return new UserProfile(
            resultSet.getLong(1),
            resultSet.getString(2),
            resultSet.getBytes(3)
        );
    }
}
