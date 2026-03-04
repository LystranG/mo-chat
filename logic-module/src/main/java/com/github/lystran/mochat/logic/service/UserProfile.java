package com.github.lystran.mochat.logic.service;

import java.util.Arrays;

public record UserProfile(long userId, String username, byte[] identityPublicKey) {
    public UserProfile {
        identityPublicKey = Arrays.copyOf(identityPublicKey, identityPublicKey.length);
    }

    @Override
    public byte[] identityPublicKey() {
        return Arrays.copyOf(identityPublicKey, identityPublicKey.length);
    }
}
