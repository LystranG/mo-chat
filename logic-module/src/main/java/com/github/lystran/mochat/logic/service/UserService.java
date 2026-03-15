package com.github.lystran.mochat.logic.service;

import jakarta.inject.Singleton;
import org.apache.commons.codec.CodecPolicy;
import org.apache.commons.codec.binary.Base64;

import java.util.Arrays;
import java.util.Objects;

/**
 * 处理登录注册时的用户名、公钥校验和用户资料装配。
 */
@Singleton
public final class UserService {
    private static final Base64 BASE64_DECODER = new Base64(0, null, false, CodecPolicy.STRICT);

    private final UserRepository userRepository;

    /**
     * 使用用户仓储构造登录服务。
     */
    public UserService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    /**
     * 按“首次登录创建、再次登录校验公钥一致性”的规则返回用户资料。
     */
    public UserProfile loginOrRegister(String username, String encodedPublicKey) {
        String normalizedUsername = normalizeUsername(username);
        var existingUser = userRepository.findByUsername(normalizedUsername);
        if (existingUser.isEmpty()) {
            if (!hasText(encodedPublicKey)) {
                throw new AuthValidationException("publicKey is required for first-time login");
            }

            // 首次登录必须把公钥持久化下来，后续所有登录都以这份身份公钥为准。
            byte[] candidateKey = decodeAndValidateKey(encodedPublicKey);
            UserProfile createdProfile = userRepository.create(normalizedUsername, candidateKey);
            if (!Arrays.equals(createdProfile.identityPublicKey(), candidateKey)) {
                throw new AuthValidationException("publicKey does not match persisted identity key");
            }
            return createdProfile;
        }

        UserProfile userProfile = existingUser.get();
        if (hasText(encodedPublicKey)) {
            // 已存在用户若再次显式提交公钥，必须与持久化值一致，防止会话劫持或身份漂移。
            byte[] candidateKey = decodeAndValidateKey(encodedPublicKey);
            if (!Arrays.equals(userProfile.identityPublicKey(), candidateKey)) {
                throw new AuthValidationException("publicKey does not match persisted identity key");
            }
        }

        return userProfile;
    }

    /**
     * 规范化并校验用户名。
     */
    private static String normalizeUsername(String username) {
        if (!hasText(username)) {
            throw new AuthValidationException("username is required");
        }
        return username.trim();
    }

    /**
     * 解析并校验 Base64 编码的身份公钥。
     */
    private static byte[] decodeAndValidateKey(String encodedPublicKey) {
        byte[] decoded;
        try {
            decoded = BASE64_DECODER.decode(encodedPublicKey);
        } catch (IllegalArgumentException exception) {
            throw new AuthValidationException("publicKey must be valid base64");
        }

        if (decoded == null || decoded.length != 32) {
            throw new AuthValidationException("publicKey must decode to 32 bytes");
        }

        return decoded;
    }

    /**
     * 判断字符串是否包含有效文本内容。
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
