package com.github.lystran.mochat.logic.service;

import jakarta.inject.Singleton;
import org.apache.commons.codec.CodecPolicy;
import org.apache.commons.codec.binary.Base64;

import java.util.Arrays;
import java.util.Objects;

@Singleton
public final class UserService {
    private static final Base64 BASE64_DECODER = new Base64(0, null, false, CodecPolicy.STRICT);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    public UserProfile loginOrRegister(String username, String encodedPublicKey) {
        String normalizedUsername = normalizeUsername(username);
        var existingUser = userRepository.findByUsername(normalizedUsername);
        if (existingUser.isEmpty()) {
            if (!hasText(encodedPublicKey)) {
                throw new AuthValidationException("publicKey is required for first-time login");
            }

            return userRepository.create(normalizedUsername, decodeAndValidateKey(encodedPublicKey));
        }

        UserProfile userProfile = existingUser.get();
        if (hasText(encodedPublicKey)) {
            byte[] candidateKey = decodeAndValidateKey(encodedPublicKey);
            if (!Arrays.equals(userProfile.identityPublicKey(), candidateKey)) {
                throw new AuthValidationException("publicKey does not match persisted identity key");
            }
        }

        return userProfile;
    }

    private static String normalizeUsername(String username) {
        if (!hasText(username)) {
            throw new AuthValidationException("username is required");
        }
        return username.trim();
    }

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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
