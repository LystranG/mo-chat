package com.github.lystran.mochat.logic.http;

import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Factory;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class AuthControllerHttpTest {
    @Inject
    @Client("/")
    HttpClient httpClient;

    @Test
    void newUserWithoutPublicKeyIsRejected() {
        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> httpClient.toBlocking().exchange(HttpRequest.POST("/auth/login", Map.of("username", "http-no-key")), Map.class)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void newUserWithValidPublicKeyIsAccepted() {
        String publicKey = encodeKey((byte) 7);

        var response = httpClient.toBlocking().exchange(
            HttpRequest.POST("/auth/login", Map.of("username", "http-valid-key", "publicKey", publicKey)),
            Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatus());
        Map<?, ?> body = response.body();
        assertNotNull(body);
        assertEquals("http-valid-key", body.get("username"));
        assertTrue(body.get("sessionId") instanceof String sessionId && !sessionId.isBlank());
    }

    @Test
    void existingUserWithMismatchedPublicKeyIsRejected() {
        httpClient.toBlocking().exchange(
            HttpRequest.POST("/auth/login", Map.of("username", "http-mismatch", "publicKey", encodeKey((byte) 1))),
            Map.class
        );

        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> httpClient.toBlocking().exchange(
                HttpRequest.POST("/auth/login", Map.of("username", "http-mismatch", "publicKey", encodeKey((byte) 2))),
                Map.class
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    private static String encodeKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }

    @Factory
    static class TestBeans {
        @jakarta.inject.Singleton
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands() {
            return Mockito.mock(RedisCommands.class);
        }
    }
}
