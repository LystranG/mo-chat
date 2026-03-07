package com.github.lystran.mochat.logic.service;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
@Requires(missingBeans = UserRepository.class)
public final class InMemoryUserRepository implements UserRepository {
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final ConcurrentMap<String, UserProfile> usersByUsername = new ConcurrentHashMap<>();

    @Override
    public Optional<UserProfile> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    @Override
    public UserProfile create(String username, byte[] identityPublicKey) {
        return usersByUsername.computeIfAbsent(
            username,
            ignored -> new UserProfile(idGenerator.getAndIncrement(), username, Arrays.copyOf(identityPublicKey, identityPublicKey.length))
        );
    }
}
