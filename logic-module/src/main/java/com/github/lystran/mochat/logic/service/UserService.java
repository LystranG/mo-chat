package com.github.lystran.mochat.logic.service;

import jakarta.inject.Singleton;
import org.apache.commons.codec.CodecPolicy;
import org.apache.commons.codec.binary.Base64;

import java.util.Arrays;
import java.util.Objects;

/**
 * 处理登录与首次注册时的用户名、公钥校验逻辑。
 */
@Singleton
public final class UserService {
    /** 严格 base64 解码器，用来拒绝格式有问题的公钥字符串。 */
    private static final Base64 BASE64_DECODER = new Base64(0, null, false, CodecPolicy.STRICT);

    private final UserRepository userRepository;

    /**
     * 创建用户登录服务。
     */
    public UserService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    /**
     * 已有用户就校验公钥，首次登录则补建用户资料。
     */
    public UserProfile loginOrRegister(String username, String encodedPublicKey) {
        String normalizedUsername = normalizeUsername(username);
        var existingUser = userRepository.findByUsername(normalizedUsername);
        if (existingUser.isEmpty()) {
            // 首次登录必须带公钥，后续同名登录都要和这把公钥保持一致。
            if (!hasText(encodedPublicKey)) {
                throw new AuthValidationException("publicKey is required for first-time login");
            }

            byte[] candidateKey = decodeAndValidateKey(encodedPublicKey);
            UserProfile createdProfile = userRepository.create(normalizedUsername, candidateKey);
            // 并发首登时可能是别的请求先落库，这里再核对一次最终落下去的是不是同一把公钥。
            if (!Arrays.equals(createdProfile.identityPublicKey(), candidateKey)) {
                throw new AuthValidationException("publicKey does not match persisted identity key");
            }
            return createdProfile;
        }

        UserProfile userProfile = existingUser.get();
        if (hasText(encodedPublicKey)) {
            // 已有账号允许重复带公钥登录，但公钥必须和历史登记的一致。
            byte[] candidateKey = decodeAndValidateKey(encodedPublicKey);
            if (!Arrays.equals(userProfile.identityPublicKey(), candidateKey)) {
                throw new AuthValidationException("publicKey does not match persisted identity key");
            }
        }

        return userProfile;
    }

    /**
     * 规范用户名，顺手拦掉空白用户名。
     */
    private static String normalizeUsername(String username) {
        if (!hasText(username)) {
            throw new AuthValidationException("username is required");
        }
        return username.trim();
    }

    /**
     * 把 base64 公钥解码成 32 字节数组，不符合要求就直接拒绝。
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
     * 判断字符串是不是有实际内容。
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
