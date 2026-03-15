package com.github.lystran.mochat.logic.service;

import java.util.Arrays;

/**
 * 表示一个用户的基础资料和身份公钥。
 */
public record UserProfile(long userId, String username, byte[] identityPublicKey) {
    /**
     * 创建用户资料时复制公钥数组，避免外部继续改动原始字节。
     */
    public UserProfile {
        identityPublicKey = Arrays.copyOf(identityPublicKey, identityPublicKey.length);
    }

    /**
     * 返回公钥时再复制一份，避免调用方拿到内部数组后直接改值。
     */
    @Override
    public byte[] identityPublicKey() {
        return Arrays.copyOf(identityPublicKey, identityPublicKey.length);
    }
}
