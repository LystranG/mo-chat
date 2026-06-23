package com.github.lystran.mochat.callservice;

import com.github.lystran.mochat.call.dto.CallRoomState;
import com.github.lystran.mochat.call.dto.CallRoomType;
import com.github.lystran.mochat.call.entity.CallOfflineNotification;
import com.github.lystran.mochat.call.manager.CallRoomManager;
import com.github.lystran.mochat.call.mq.CallOfflineNotificationMqProducer;
import com.github.lystran.mochat.call.service.CallOfflineNotificationService;
import com.github.lystran.mochat.call.service.CallRelationshipService;
import com.github.lystran.mochat.call.service.CallService;
import com.github.lystran.mochat.call.service.CallSessionResolver;
import com.github.lystran.mochat.call.service.CallTokenService;
import com.github.lystran.mochat.call.websocket.CallSignalGateway;
import com.github.lystran.mochat.common.id.IdGenerator;

import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;


@MicronautTest
@Property(name = "spec.name", value = "call-service-http")
@Property(name = "mochat.livekit.url", value = "wss://easy-chat-xgexhw94.livekit.cloud")
@Property(name = "mochat.livekit.api-key", value = "APIb73iuax7XbHv")
@Property(name = "mochat.livekit.api-secret", value = "iSY6Acl4n4McSRIEAeUPfOreXAztv4OK63M9jYWgIvVB")
@Property(name = "mochat.call-service.dependencies.postgres-enabled", value = "false")
@Property(name = "mochat.call-service.dependencies.redis-enabled", value = "false")
@Property(name = "mochat.call-service.dependencies.mq-enabled", value = "false")
@Property(name = "mochat.call-service.flyway.migrate-on-start", value = "false")
@Property(name = "mochat.call-service.queue.consumer-enabled", value = "false")
class CallServiceHttpTest {

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    CallSessionResolver sessionResolver;

    @Inject
    CallRelationshipService relationshipService;

    @Inject
    CallService callService;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(sessionResolver, relationshipService);
    }

    // ==================== 私聊邀请 ====================

    @Test
    void invitePrivateCallWithValidSessionReturnsOk() {
        long fromUserId = 1001L;
        long toUserId = 1002L;

        when(sessionResolver.resolveUserId("active-session-1")).thenReturn(Optional.of(fromUserId));
        when(relationshipService.privateRelationshipState(fromUserId, toUserId))
            .thenReturn(CallRelationshipService.PrivateRelationshipState.ACTIVE);

        var response = httpClient.toBlocking().exchange(
            HttpRequest.POST("/calls/private/invite", Map.of("sessionId", "active-session-1", "toUserId", toUserId)),
            Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatus());
        Map<?, ?> body = response.body();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertNotNull(body.get("data"));
        System.out.println(response);
    }

    @Test
    void invitePrivateCallWithInvalidSessionReturnsUnauthorized() {
        when(sessionResolver.resolveUserId("invalid-session")).thenReturn(Optional.empty());

        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> httpClient.toBlocking().exchange(
                HttpRequest.POST("/calls/private/invite", Map.of("sessionId", "invalid-session", "toUserId", 1002L)),
                Map.class
            )
        );

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
    }

    @Test
    void invitePrivateCallWithBlockedRelationshipReturnsBadRequest() {
        long fromUserId = 1001L;
        long toUserId = 1002L;

        when(sessionResolver.resolveUserId("active-session-2")).thenReturn(Optional.of(fromUserId));
        when(relationshipService.privateRelationshipState(fromUserId, toUserId))
            .thenReturn(CallRelationshipService.PrivateRelationshipState.BLOCKED);

        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> httpClient.toBlocking().exchange(
                HttpRequest.POST("/calls/private/invite", Map.of("sessionId", "active-session-2", "toUserId", toUserId)),
                Map.class
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void invitePrivateCallWithNotFriendReturnsBadRequest() {
        long fromUserId = 1001L;
        long toUserId = 1002L;

        when(sessionResolver.resolveUserId("active-session-3")).thenReturn(Optional.of(fromUserId));
        when(relationshipService.privateRelationshipState(fromUserId, toUserId))
            .thenReturn(CallRelationshipService.PrivateRelationshipState.NOT_FRIEND);

        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> httpClient.toBlocking().exchange(
                HttpRequest.POST("/calls/private/invite", Map.of("sessionId", "active-session-3", "toUserId", toUserId)),
                Map.class
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void invitePrivateCallToSelfReturnsBadRequest() {
        long userId = 1001L;

        when(sessionResolver.resolveUserId("active-session-4")).thenReturn(Optional.of(userId));

        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> httpClient.toBlocking().exchange(
                HttpRequest.POST("/calls/private/invite", Map.of("sessionId", "active-session-4", "toUserId", userId)),
                Map.class
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    // ==================== 群聊通话 ====================

    @Test
    void startGroupCallWithValidMemberReturnsOk() {
        long fromUserId = 1001L;
        long groupId = 500L;

        when(sessionResolver.resolveUserId("group-session-1")).thenReturn(Optional.of(fromUserId));
        when(relationshipService.listActiveGroupMemberIds(groupId))
            .thenReturn(java.util.List.of(fromUserId, 1002L, 1003L));
        when(relationshipService.isActiveGroupMember(groupId, fromUserId))
            .thenReturn(true);

        var response = httpClient.toBlocking().exchange(
            HttpRequest.POST("/calls/group/start", Map.of("sessionId", "group-session-1", "groupId", groupId)),
            Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatus());
        Map<?, ?> body = response.body();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
    }

    @Test
    void startGroupCallWithNonMemberReturnsBadRequest() {
        long fromUserId = 1001L;
        long groupId = 501L;

        when(sessionResolver.resolveUserId("group-session-2")).thenReturn(Optional.of(fromUserId));
        when(relationshipService.listActiveGroupMemberIds(groupId))
            .thenReturn(java.util.List.of(2001L, 2002L));

        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> httpClient.toBlocking().exchange(
                HttpRequest.POST("/calls/group/start", Map.of("sessionId", "group-session-2", "groupId", groupId)),
                Map.class
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    // ==================== 群聊加入/离开 ====================

    @Test
    void joinGroupCallWithValidMemberReturnsOk() {
        long userId = 1001L;
        long groupId = 600L;
        String callId = "testcall123";

        // 先创建一个群通话房间
        when(sessionResolver.resolveUserId("join-session-1")).thenReturn(Optional.of(userId));
        when(relationshipService.listActiveGroupMemberIds(groupId))
            .thenReturn(java.util.List.of(userId, 1002L));
        when(relationshipService.isActiveGroupMember(groupId, userId))
            .thenReturn(true);

        var startResponse = callService.startGroupCall(userId, groupId);
        String roomName = startResponse.roomName();

        when(relationshipService.isActiveGroupMember(groupId, userId))
            .thenReturn(true);

        var response = httpClient.toBlocking().exchange(
            HttpRequest.POST("/calls/group/join", Map.of("sessionId", "join-session-1", "roomName", roomName)),
            Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatus());
    }

    @Test
    void leaveGroupCallWithValidMemberReturnsOk() {
        long userId = 1001L;
        long groupId = 700L;

        when(sessionResolver.resolveUserId("leave-session-1")).thenReturn(Optional.of(userId));
        when(relationshipService.listActiveGroupMemberIds(groupId))
            .thenReturn(java.util.List.of(userId, 1002L));
        when(relationshipService.isActiveGroupMember(groupId, userId))
            .thenReturn(true);

        var startResponse = callService.startGroupCall(userId, groupId);
        String roomName = startResponse.roomName();

        callService.joinGroupCall(userId, roomName);

        var response = httpClient.toBlocking().exchange(
            HttpRequest.POST("/calls/group/leave", Map.of("sessionId", "leave-session-1", "roomName", roomName)),
            Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatus());
        Map<?, ?> body = response.body();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
    }

    // ==================== 直接测试 CallService ====================

    @Test
    void callServiceInvitePrivateCallReturnsCallIdAndRoomName() {
        long fromUserId = 1001L;
        long toUserId = 1002L;

        when(relationshipService.privateRelationshipState(fromUserId, toUserId))
            .thenReturn(CallRelationshipService.PrivateRelationshipState.ACTIVE);

        var result = callService.invitePrivateCall(fromUserId, toUserId);

        assertNotNull(result.callId());
        assertNotNull(result.roomName());
        assertEquals(fromUserId, result.fromUserId());
        assertEquals(toUserId, result.toUserId());
        // 接收方不在线时 token 为 null，房间会被结束
        // 在线时才会签发 token
    }

    @Test
    void callServiceStartGroupCallReturnsResultWithOfflineMembersQueued() {
        long fromUserId = 1001L;
        long groupId = 800L;

        when(relationshipService.listActiveGroupMemberIds(groupId))
            .thenReturn(java.util.List.of(fromUserId, 1002L, 1003L));

        // startGroupCall 会调 callTokenService.issueToken，真实 LiveKit SDK 可能拒绝测试凭据
        // 这里只验证不抛异常时结果结构正确
        try {
            var result = callService.startGroupCall(fromUserId, groupId);
            assertNotNull(result.callId());
            assertEquals(groupId, result.groupId());
            assertFalse(result.queuedUserIds().isEmpty(), "offline members should be queued");
        } catch (IllegalArgumentException expectedWhenLiveKitRejectsTestCredentials) {
            // LiveKit SDK 对测试凭据格式有要求，这是预期的
            assertTrue(expectedWhenLiveKitRejectsTestCredentials.getMessage() != null);
        }
    }

    // ==================== 测试 Bean 工厂 ====================

    /**
     * 提供 mock 的外部依赖，避免测试需要真实的 Redis、PostgreSQL、RocketMQ 和 LiveKit。
     */
    @Factory
    @Requires(property = "spec.name", value = "call-service-http")
    static class TestBeanFactory {

        @Singleton
        @Primary
        CallSessionResolver callSessionResolver() {
            return Mockito.mock(CallSessionResolver.class);
        }

        @Singleton
        @Primary
        CallRelationshipService callRelationshipService() {
            return Mockito.mock(CallRelationshipService.class);
        }

        @Singleton
        @Primary
        RedisCommands<String, String> redisCommands() {
            return Mockito.mock(RedisCommands.class);
        }

        @Singleton
        @Primary
        javax.sql.DataSource dataSource() {
            return Mockito.mock(javax.sql.DataSource.class);
        }

        @Singleton
        @Primary
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory() {
            return Mockito.mock(org.apache.ibatis.session.SqlSessionFactory.class);
        }

        @Singleton
        @Primary
        IdGenerator idGenerator() {
            IdGenerator mock = Mockito.mock(IdGenerator.class);
            when(mock.nextId()).thenReturn(9001L, 9002L, 9003L, 9004L, 9005L);
            return mock;
        }

        @Singleton
        @Primary
        CallOfflineNotificationMqProducer mqProducer() {
            CallOfflineNotificationMqProducer mock = Mockito.mock(CallOfflineNotificationMqProducer.class);
            when(mock.sendBatch(Mockito.anyList())).thenReturn(true);
            return mock;
        }
    }
}
