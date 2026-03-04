package com.github.lystran.mochat.logic.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserServiceTest {
    @Test
    void rejectsFirstTimeLoginWithoutPublicKey() {
        UserService userService = new UserService(new InMemoryUserRepository());

        assertThrows(AuthValidationException.class, () -> userService.loginOrRegister("alice", null));
    }

    @Test
    void rejectsFirstTimeLoginWhenPublicKeyCannotBeDecoded() {
        UserService userService = new UserService(new InMemoryUserRepository());

        assertThrows(AuthValidationException.class, () -> userService.loginOrRegister("alice", "%%%"));
    }

    @Test
    void rejectsFirstTimeLoginWhenPublicKeyLengthIsNotThirtyTwoBytes() {
        UserService userService = new UserService(new InMemoryUserRepository());

        assertThrows(AuthValidationException.class, () -> userService.loginOrRegister("alice", encodeKey(31, (byte) 7)));
    }

    @Test
    void rejectsExistingUserWithMismatchedPublicKey() {
        UserService userService = new UserService(new InMemoryUserRepository());
        userService.loginOrRegister("alice", encodeKey(32, (byte) 7));

        assertThrows(
            AuthValidationException.class,
            () -> userService.loginOrRegister("alice", encodeKey(32, (byte) 9))
        );
    }

    @Test
    void allowsExistingUserWithoutResendingPublicKey() {
        UserService userService = new UserService(new InMemoryUserRepository());
        var created = userService.loginOrRegister("alice", encodeKey(32, (byte) 7));

        var authenticated = userService.loginOrRegister("alice", null);

        assertEquals(created.userId(), authenticated.userId());
    }

    private static String encodeKey(int size, byte value) {
        byte[] key = new byte[size];
        for (int i = 0; i < key.length; i++) {
            key[i] = value;
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
