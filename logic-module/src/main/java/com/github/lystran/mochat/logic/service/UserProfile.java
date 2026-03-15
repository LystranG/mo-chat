package com.github.lystran.mochat.logic.service;

import java.util.Arrays;

/**
 * 表示登录流程里使用的一份用户资料副本。
 */
public record UserProfile(long userId, String username, byte[] identityPublicKey) {
    /**
     * 在构造时复制公钥数组，避免外部持有可变引用。
     */
    public UserProfile {
        identityPublicKey = Arrays.copyOf(identityPublicKey, identityPublicKey.length);
    }

    /**
     * 返回公钥内容的防御性副本。
     */
    @Override
    public byte[] identityPublicKey() {
        return Arrays.copyOf(identityPublicKey, identityPublicKey.length);
    }
}
