package com.github.lystran.mochat.messageservice;

import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.infra.redis.RedisOfflineQueue;
import com.github.lystran.mochat.logic.chat.MessageRecipientDispatcher;
import com.github.lystran.mochat.messageservice.grpc.AccessGatewayDispatchClientFactory;
import com.github.lystran.mochat.messageservice.grpc.GrpcMessageRecipientDispatcher;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MessageServiceGrpcWiringTest {
    private static final String DISPATCHER_WIRING_SPEC = "message-service-dispatcher-wiring";
    private static final String NO_REDIS_WIRING_SPEC = "message-service-no-redis-wiring";

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void exposesCommandGrpcServerAndGatewayClientFactory() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true
        ))) {
            Class serviceType = Class.forName("com.github.lystran.mochat.messageservice.grpc.MessageCommandGrpcService");
            Class apiStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc$SessionAuthorityApiBlockingStub"
            );
            Class gatewayStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc$AccessGatewayDispatchApiBlockingStub"
            );
            Class gatewayFactoryType = Class.forName(
                "com.github.lystran.mochat.messageservice.grpc.AccessGatewayDispatchClientFactory"
            );
            assertTrue(context.containsBean(serviceType));
            assertTrue(context.containsBean(apiStubType));
            assertTrue(context.containsBean(gatewayFactoryType));
            assertFalse(context.containsBean(gatewayStubType));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dedicatedRuntimeUsesGrpcPolicyGatewayInsteadOfRepositoryFallback() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", NO_REDIS_WIRING_SPEC,
            "grpc.server.port", 0,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false
        ))) {
            Class policyGatewayType = Class.forName("com.github.lystran.mochat.logic.chat.MessageSendPolicyGateway");
            Class grpcPolicyGatewayType = Class.forName("com.github.lystran.mochat.messageservice.grpc.GrpcMessageSendPolicyGateway");
            Class repositoryPolicyGatewayType = Class.forName(
                "com.github.lystran.mochat.logic.chat.RepositoryBackedMessageSendPolicyGateway"
            );
            Object policyGateway = context.getBean(policyGatewayType);

            assertTrue(context.containsBean(policyGatewayType));
            assertTrue(grpcPolicyGatewayType.isInstance(policyGateway));
            assertFalse(context.containsBean(repositoryPolicyGatewayType));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dedicatedRuntimeDoesNotFallbackToRepositoryPolicyWhenApiGrpcIsDisabled() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", NO_REDIS_WIRING_SPEC,
            "grpc.server.port", 0,
            "mochat.message-service.dependencies.api-grpc-enabled", false,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false
        ))) {
            Class policyGatewayType = Class.forName("com.github.lystran.mochat.logic.chat.MessageSendPolicyGateway");
            Class grpcPolicyGatewayType = Class.forName("com.github.lystran.mochat.messageservice.grpc.GrpcMessageSendPolicyGateway");
            Class repositoryPolicyGatewayType = Class.forName(
                "com.github.lystran.mochat.logic.chat.RepositoryBackedMessageSendPolicyGateway"
            );

            assertFalse(context.containsBean(policyGatewayType));
            assertFalse(context.containsBean(grpcPolicyGatewayType));
            assertFalse(context.containsBean(repositoryPolicyGatewayType));
        }
    }

    @Test
    void createsGatewayClientPerTargetAddress() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true
        ))) {
            Object factory = context.getBean(Class.forName(
                "com.github.lystran.mochat.messageservice.grpc.AccessGatewayDispatchClientFactory"
            ));
            Object first = factory.getClass().getMethod("createBlockingStub", String.class).invoke(factory, "gateway-a:19093");
            Object second = factory.getClass().getMethod("createBlockingStub", String.class).invoke(factory, "gateway-b:19093");
            Object firstChannel = first.getClass().getMethod("getChannel").invoke(first);
            Object secondChannel = second.getClass().getMethod("getChannel").invoke(second);

            assertNotSame(firstChannel, secondChannel);
        }
    }

    @Test
    void dedicatedRuntimeUsesGrpcRecipientDispatcherWhenRedisAndGatewayClientBeansAreAvailable() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", DISPATCHER_WIRING_SPEC,
            "grpc.server.port", 0,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false
        ))) {
            MessageRecipientDispatcher dispatcher = context.getBean(MessageRecipientDispatcher.class);

            assertTrue(context.containsBean(MessageRecipientDispatcher.class));
            assertSame(GrpcMessageRecipientDispatcher.class, dispatcher.getClass());
        }
    }

    @Test
    void dedicatedRuntimeUsesRedisOfflineQueueWhenManualRedisCommandsBeanIsPresent() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", DISPATCHER_WIRING_SPEC,
            "grpc.server.port", 0,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false
        ))) {
            OfflineQueue offlineQueue = context.getBean(OfflineQueue.class);

            assertTrue(context.containsBean(OfflineQueue.class));
            assertSame(RedisOfflineQueue.class, offlineQueue.getClass());
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dedicatedRuntimeDoesNotMaterializeReceiptStateFallbackOwner() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", NO_REDIS_WIRING_SPEC,
            "grpc.server.port", 0,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false
        ))) {
            Class receiptStateStoreType = Class.forName("com.github.lystran.mochat.logic.chat.ReceiptConversationStateStore");
            Class inMemoryReceiptStateStoreType = Class.forName("com.github.lystran.mochat.logic.chat.InMemoryReceiptConversationStateStore");

            assertFalse(context.containsBean(receiptStateStoreType));
            assertFalse(context.containsBean(inMemoryReceiptStateStoreType));
        }
    }

    @Factory
    @Requires(property = "spec.name", value = DISPATCHER_WIRING_SPEC)
    static final class DispatcherWiringTestFactory {
        @Singleton
        RedisCommands<String, String> redisCommands() {
            return mock(RedisCommands.class);
        }

        @Singleton
        AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory() {
            return mock(AccessGatewayDispatchClientFactory.class);
        }
    }

    @Factory
    @Requires(property = "spec.name", value = NO_REDIS_WIRING_SPEC)
    static final class NoRedisWiringTestFactory {
        @Singleton
        OfflineQueue offlineQueue() {
            return new OfflineQueue() {
                @Override
                public void enqueue(long userId, String payload, int maxQueueSize) {
                }

                @Override
                public java.util.List<String> drain(long userId, int maxItems) {
                    return java.util.List.of();
                }
            };
        }
    }
}
