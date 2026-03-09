package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.chat.InboundMessageConsumer;
import com.github.lystran.mochat.logic.chat.OfflineReplayService;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.logic.service.FriendsService;
import com.github.lystran.mochat.logic.service.GroupsService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@MicronautTest
@Property(name = "spec.name", value = SessionAuthHttpIntegrationTest.SPEC_NAME)
class SessionAuthHttpIntegrationTest {
    static final String SPEC_NAME = "session-auth-http";

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Test
    void protectedEndpointsRejectInvalidSession() {
        HttpClientResponseException friendsException = assertThrows(
            HttpClientResponseException.class,
            () -> httpClient.toBlocking().exchange(HttpRequest.GET("/friends?sessionId=expired-session"), Object.class)
        );
        HttpClientResponseException groupsException = assertThrows(
            HttpClientResponseException.class,
            () -> httpClient.toBlocking().exchange(HttpRequest.GET("/groups?sessionId=expired-session"), Object.class)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, friendsException.getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, groupsException.getStatus());
    }

    @Factory
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class TestBeans {
        @jakarta.inject.Singleton
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands() {
            return Mockito.mock(RedisCommands.class);
        }

        @jakarta.inject.Singleton
        @Replaces(SessionService.class)
        SessionService sessionService() {
            SessionService sessionService = Mockito.mock(SessionService.class);
            when(sessionService.resolveUserId(anyString())).thenReturn(Optional.empty());
            return sessionService;
        }

        @jakarta.inject.Singleton
        @Replaces(FriendsService.class)
        FriendsService friendsService() {
            return Mockito.mock(FriendsService.class);
        }

        @jakarta.inject.Singleton
        @Replaces(GroupsService.class)
        GroupsService groupsService() {
            return Mockito.mock(GroupsService.class);
        }

        @jakarta.inject.Singleton
        @Replaces(OfflineReplayService.class)
        OfflineReplayService offlineReplayService() {
            return Mockito.mock(OfflineReplayService.class);
        }

        @jakarta.inject.Singleton
        @Replaces(InboundMessageConsumer.class)
        InboundMessageConsumer inboundMessageConsumer() {
            return Mockito.mock(InboundMessageConsumer.class);
        }

        @jakarta.inject.Singleton
        @Primary
        EventBus eventBus() {
            return new InProcessEventBus();
        }

        @jakarta.inject.Singleton
        ConversationLock conversationLock() {
            return new JucConversationLock();
        }

        @jakarta.inject.Singleton
        IdempotencyStore idempotencyStore() {
            return Mockito.mock(IdempotencyStore.class);
        }

        @jakarta.inject.Singleton
        ConversationSeqGenerator conversationSeqGenerator() {
            return Mockito.mock(ConversationSeqGenerator.class);
        }

        @jakarta.inject.Singleton
        IdGenerator idGenerator() {
            return Mockito.mock(IdGenerator.class);
        }

        @jakarta.inject.Singleton
        @Replaces(RocketMqProducer.class)
        RocketMqProducer rocketMqProducer() {
            return Mockito.mock(RocketMqProducer.class);
        }
    }
}
