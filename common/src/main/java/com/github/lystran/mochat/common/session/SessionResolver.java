package com.github.lystran.mochat.common.session;

import java.util.Optional;

/**
 * 用来根据 sessionId 找到当前登录用户。
 */
public interface SessionResolver {
    /**
     * 根据 sessionId 查当前登录用户 ID。
     */
    Optional<Long> resolveUserId(String sessionId);
}
