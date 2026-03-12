package com.github.lystran.mochat.apiservice;

import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import com.github.lystran.mochat.logic.service.SessionService;
import com.github.lystran.mochat.protocol.internal.api.v1.CheckPrivateMessagingPolicyRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.GetGroupSendContextRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.GroupSendEligibility;
import com.github.lystran.mochat.protocol.internal.api.v1.PrivateMessagingPolicy;
import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionResolutionStatus;
import io.grpc.Channel;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiServiceGrpcConnectivityTest {
    private static final String SPEC_NAME = "api-service-session-authority-grpc";

    @Test
    void resolvesIssuedSessionOverMicronautGrpcServerChannel() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0
        ))) {
            SessionService sessionService = context.getBean(SessionService.class);
            var stub = context.getBean(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);
            String sessionId = sessionService.issueSession(42L);
            var active = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(sessionId).build());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE, active.getStatus());
            assertEquals(42L, active.getPrincipal().getUserId());
            assertEquals(1L, active.getPrincipal().getSessionVersion());
        }
    }

    @Test
    void resolvesExpiredAndInvalidSessionStatusesOverMicronautGrpcServerChannel() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0
        ))) {
            SessionAuthorityRedisStore store = context.getBean(SessionAuthorityRedisStore.class);
            var stub = context.getBean(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);
            store.put("mochat:session:expired-session", "v1|ACTIVE|42|7|1");

            var expired = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId("expired-session").build());
            var invalid = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId("missing-session").build());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_EXPIRED, expired.getStatus());
            assertEquals(42L, expired.getPrincipal().getUserId());
            assertEquals(7L, expired.getPrincipal().getSessionVersion());
            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_INVALID, invalid.getStatus());
        }
    }

    @Test
    void newerIssuedSessionReplacesEarlierSessionForSameUser() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0
        ))) {
            SessionService sessionService = context.getBean(SessionService.class);
            var stub = context.getBean(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);

            String firstSessionId = sessionService.issueSession(42L);
            String secondSessionId = sessionService.issueSession(42L);

            var replaced = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(firstSessionId).build());
            var active = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(secondSessionId).build());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_REPLACED, replaced.getStatus());
            assertEquals(42L, replaced.getPrincipal().getUserId());
            assertEquals(1L, replaced.getPrincipal().getSessionVersion());
            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE, active.getStatus());
            assertEquals(42L, active.getPrincipal().getUserId());
            assertEquals(2L, active.getPrincipal().getSessionVersion());
        }
    }

    @Test
    void mapsPrivateMessagingPolicyFromRelationshipStateOverMicronautGrpcServerChannel() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0
        ))) {
            TestMessageRelationshipRepository relationshipRepository = context.getBean(TestMessageRelationshipRepository.class);
            var stub = context.getBean(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);

            relationshipRepository.privateState(MessageRelationshipRepository.PrivateMessageState.NOT_FRIEND);
            var notFriend = stub.checkPrivateMessagingPolicy(CheckPrivateMessagingPolicyRequest.newBuilder()
                .setConversationId(200L)
                .setSenderUid(88L)
                .setRecipientUid(11L)
                .build());
            assertEquals(PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_NOT_FRIEND, notFriend.getPolicy());

            relationshipRepository.privateState(MessageRelationshipRepository.PrivateMessageState.BLOCKED);
            var blocked = stub.checkPrivateMessagingPolicy(CheckPrivateMessagingPolicyRequest.newBuilder()
                .setConversationId(200L)
                .setSenderUid(88L)
                .setRecipientUid(11L)
                .build());
            assertEquals(PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_BLOCKED, blocked.getPolicy());

            relationshipRepository.privateState(MessageRelationshipRepository.PrivateMessageState.ACTIVE);
            var allowed = stub.checkPrivateMessagingPolicy(CheckPrivateMessagingPolicyRequest.newBuilder()
                .setConversationId(200L)
                .setSenderUid(88L)
                .setRecipientUid(11L)
                .build());
            assertEquals(PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_ALLOWED, allowed.getPolicy());
            assertEquals(200L, relationshipRepository.lastConversationId());
            assertEquals(11L, relationshipRepository.lastPeerUidLow());
            assertEquals(88L, relationshipRepository.lastPeerUidHigh());
        }
    }

    @Test
    void mapsGroupSendContextFromRelationshipStateOverMicronautGrpcServerChannel() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0
        ))) {
            TestMessageRelationshipRepository relationshipRepository = context.getBean(TestMessageRelationshipRepository.class);
            var stub = context.getBean(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);

            relationshipRepository.groupExists(false);
            var groupMissing = stub.getGroupSendContext(GetGroupSendContextRequest.newBuilder()
                .setGroupId(300L)
                .setSenderUid(11L)
                .build());
            assertEquals(GroupSendEligibility.GROUP_SEND_ELIGIBILITY_GROUP_NOT_FOUND, groupMissing.getEligibility());
            assertEquals(0, groupMissing.getMemberUidsCount());

            relationshipRepository.groupExists(true);
            relationshipRepository.activeGroupMember(false);
            var notMember = stub.getGroupSendContext(GetGroupSendContextRequest.newBuilder()
                .setGroupId(300L)
                .setSenderUid(11L)
                .build());
            assertEquals(GroupSendEligibility.GROUP_SEND_ELIGIBILITY_NOT_MEMBER, notMember.getEligibility());
            assertEquals(0, notMember.getMemberUidsCount());

            relationshipRepository.activeGroupMember(true);
            relationshipRepository.groupMembers(11L, 22L, 33L);
            var allowed = stub.getGroupSendContext(GetGroupSendContextRequest.newBuilder()
                .setGroupId(300L)
                .setSenderUid(11L)
                .build());
            assertEquals(GroupSendEligibility.GROUP_SEND_ELIGIBILITY_ALLOWED, allowed.getEligibility());
            assertEquals(java.util.List.of(11L, 22L, 33L), allowed.getMemberUidsList());
        }
    }

    @Factory
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestGrpcClientFactory {
        @Singleton
        SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub sessionAuthorityApiBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) Channel channel
        ) {
            return SessionAuthorityApiGrpc.newBlockingStub(channel);
        }
    }

    @Factory
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class SessionAuthorityTestFactory {
        @Singleton
        SessionAuthorityRedisStore sessionAuthorityRedisStore() {
            return new SessionAuthorityRedisStore();
        }

        @Singleton
        TestMessageRelationshipRepository testMessageRelationshipRepository() {
            return new TestMessageRelationshipRepository();
        }

        @Singleton
        @Replaces(RedisCommands.class)
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands(SessionAuthorityRedisStore store) {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> store.get((String) args[0]);
                    case "set" -> {
                        store.put((String) args[0], (String) args[1]);
                        yield "OK";
                    }
                    case "del" -> {
                        long deleted = 0L;
                        Object rawKeys = args[0];
                        if (rawKeys instanceof String[] keys) {
                            for (String key : keys) {
                                deleted += store.remove(key) != null ? 1L : 0L;
                            }
                        } else {
                            deleted += store.remove((String) rawKeys) != null ? 1L : 0L;
                        }
                        yield deleted;
                    }
                    case "toString" -> "ApiServiceGrpcConnectivityTestRedisCommands";
                    default -> throw new UnsupportedOperationException("Unsupported RedisCommands method: " + method.getName());
                }
            );
        }

        @Singleton
        @Replaces(MessageRelationshipRepository.class)
        MessageRelationshipRepository messageRelationshipRepository(TestMessageRelationshipRepository repository) {
            return repository;
        }
    }

    static final class SessionAuthorityRedisStore {
        private final ConcurrentMap<String, String> values = new ConcurrentHashMap<>();

        private String get(String key) {
            return values.get(key);
        }

        private void put(String key, String value) {
            values.put(key, value);
        }

        private String remove(String key) {
            return values.remove(key);
        }
    }

    static final class TestMessageRelationshipRepository implements MessageRelationshipRepository {
        private volatile PrivateMessageState privateState = PrivateMessageState.ACTIVE;
        private volatile boolean groupExists = true;
        private volatile boolean activeGroupMember = true;
        private volatile java.util.List<Long> groupMembers = java.util.List.of();
        private volatile long lastConversationId;
        private volatile long lastPeerUidLow;
        private volatile long lastPeerUidHigh;

        @Override
        public PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh) {
            this.lastConversationId = conversationId;
            this.lastPeerUidLow = peerUidLow;
            this.lastPeerUidHigh = peerUidHigh;
            return privateState;
        }

        @Override
        public boolean groupExists(long groupId) {
            return groupExists;
        }

        @Override
        public boolean isActiveGroupMember(long groupId, long userId) {
            return activeGroupMember;
        }

        @Override
        public java.util.List<Long> listActiveGroupMemberIds(long groupId) {
            return groupMembers;
        }

        void privateState(PrivateMessageState privateState) {
            this.privateState = privateState;
        }

        void groupExists(boolean groupExists) {
            this.groupExists = groupExists;
        }

        void activeGroupMember(boolean activeGroupMember) {
            this.activeGroupMember = activeGroupMember;
        }

        void groupMembers(Long... groupMembers) {
            this.groupMembers = java.util.List.of(groupMembers);
        }

        long lastConversationId() {
            return lastConversationId;
        }

        long lastPeerUidLow() {
            return lastPeerUidLow;
        }

        long lastPeerUidHigh() {
            return lastPeerUidHigh;
        }
    }
}
