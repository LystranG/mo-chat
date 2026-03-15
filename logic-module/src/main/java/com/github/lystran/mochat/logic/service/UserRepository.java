package com.github.lystran.mochat.logic.service;

import java.util.Optional;

/**
 * 抽象登录域对用户资料的最小读写能力。
 */
public interface UserRepository {
    /**
     * 按用户名查询用户资料。
     */
    Optional<UserProfile> findByUsername(String username);

    /**
     * 创建新的用户资料记录。
     */
    UserProfile create(String username, byte[] identityPublicKey);
}
