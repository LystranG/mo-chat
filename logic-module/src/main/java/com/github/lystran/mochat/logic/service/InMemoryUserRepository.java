package com.github.lystran.mochat.logic.service;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在缺少数据库仓储时提供基于内存的用户仓储实现。
 */
@Singleton
@Requires(missingBeans = UserRepository.class)
public final class InMemoryUserRepository implements UserRepository {
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final ConcurrentMap<String, UserProfile> usersByUsername = new ConcurrentHashMap<>();

    /**
     * 按用户名查询内存中的用户资料。
     */
    @Override
    public Optional<UserProfile> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    /**
     * 创建用户，若用户名已存在则复用既有资料。
     */
    @Override
    public UserProfile create(String username, byte[] identityPublicKey) {
        return usersByUsername.computeIfAbsent(
            username,
            ignored -> new UserProfile(idGenerator.getAndIncrement(), username, Arrays.copyOf(identityPublicKey, identityPublicKey.length))
        );
    }
}
