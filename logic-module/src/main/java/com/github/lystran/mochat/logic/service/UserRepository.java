package com.github.lystran.mochat.logic.service;

import java.util.Optional;

/**
 * 用户资料读写入口，负责用户名和身份公钥的持久化。
 */
public interface UserRepository {
    /**
     * 按用户名查找用户资料。
     */
    Optional<UserProfile> findByUsername(String username);

    /**
     * 创建新用户资料。
     */
    UserProfile create(String username, byte[] identityPublicKey);
}
