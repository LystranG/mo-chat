package com.github.lystran.mochat.logic.service;

import java.util.Optional;

public interface UserRepository {
    Optional<UserProfile> findByUsername(String username);

    UserProfile create(String username, byte[] identityPublicKey);
}
