package com.github.lystran.mochat.logic.service;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无数据库时使用的内存版用户仓储，主要方便本地或测试场景启动。
 */
@Singleton
@Requires(missingBeans = UserRepository.class)
public final class InMemoryUserRepository implements UserRepository {
    // 仅内存模式下生成临时用户 id，进程重启后会重新开始计数。
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final ConcurrentMap<String, UserProfile> usersByUsername = new ConcurrentHashMap<>();

    /**
     * 按用户名查找已经存在的用户。
     */
    @Override
    public Optional<UserProfile> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    /**
     * 按用户名创建用户；如果用户名已存在，就直接复用原有资料。
     */
    @Override
    public UserProfile create(String username, byte[] identityPublicKey) {
        return usersByUsername.computeIfAbsent(
            username,
            ignored -> new UserProfile(idGenerator.getAndIncrement(), username, Arrays.copyOf(identityPublicKey, identityPublicKey.length))
        );
    }
}
