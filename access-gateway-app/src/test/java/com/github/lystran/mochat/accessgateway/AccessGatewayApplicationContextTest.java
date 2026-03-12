package com.github.lystran.mochat.accessgateway;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionRouteWriter;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.accessgateway.runtime.AccessGatewayConnectionRuntimeLifecycle;
import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGatewayApplicationContextTest {
    @Test
    void bindsDedicatedGatewayConfigurationNamespace() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-config",
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.grpc.port", 19093,
            "mochat.access-gateway.tcp.port", 19000,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false
        ))) {
            assertEquals(19093, context.getRequiredProperty("mochat.access-gateway.grpc.port", Integer.class));
            assertEquals(19000, context.getRequiredProperty("mochat.access-gateway.tcp.port", Integer.class));
            assertFalse(context.getRequiredProperty("mochat.access-gateway.dependencies.api-grpc-enabled", Boolean.class));
        }
    }

    @Test
    void tcpDisabledDoesNotRequireTcpRuntimeBeanGraph() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-tcp-disabled-context",
            "mochat.access-gateway.runtime.enabled", true,
            "mochat.access-gateway.tcp.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false
        ))) {
            assertFalse(context.containsBean(AccessGatewayConnectionRuntimeLifecycle.class));
            assertFalse(context.containsBean(SessionBindingHandler.class));
            assertFalse(context.containsBean(NettyChatServer.class));
            assertFalse(context.containsBean(OutboundEventSubscriber.class));
            assertFalse(context.containsBean(EventBus.class));
            assertFalse(context.containsBean(SessionRouteWriter.class));
            assertFalse(context.containsBean(SslContext.class));
            assertNotNull(context.getBean(OfflineQueue.class));
            assertEquals(1, context.getBeansOfType(com.github.lystran.mochat.common.directory.UserChannelDirectory.class).size());
        }
    }

    @Test
    void assemblesGatewayOwnedConnectionRuntimeBeansWhenTcpEnabled() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-runtime-context",
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.tcp.enabled", true,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false
        ))) {
            assertNotNull(context.getBean(SessionBindingHandler.class));
            assertNotNull(context.getBean(NettyChatServer.class));
            assertNotNull(context.getBean(OutboundEventSubscriber.class));
            assertNotNull(context.getBean(EventBus.class));
            assertNotNull(context.getBean(OfflineQueue.class));
        }
    }

    @Test
    void usesLocalDropOfflineQueueForGatewayRuntime() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-tcp-disabled-context",
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.tcp.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false
        ))) {
            OfflineQueue offlineQueue = context.getBean(OfflineQueue.class);
            assertEquals("NoOpOfflineQueue", offlineQueue.getClass().getSimpleName());
        }
    }

    @Test
    void defaultUserChannelDirectoryCanBeOverridden() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-custom-directory",
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.tcp.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false
        ))) {
            assertEquals(1, context.getBeansOfType(com.github.lystran.mochat.common.directory.UserChannelDirectory.class).size());
            assertSame(
                context.getBean(CustomDirectory.class),
                context.getBean(com.github.lystran.mochat.common.directory.UserChannelDirectory.class)
            );
        }
    }

    @Test
    void missingRouteWriterPreventsSessionBindingRuntimeAssembly() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-missing-route-writer",
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.tcp.enabled", true,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false
        ))) {
            RuntimeException exception = assertThrows(RuntimeException.class, () -> context.getBean(SessionBindingHandler.class));
            assertTrue(exception.getMessage().contains("SessionRouteWriter"));
        }
    }

    @Factory
    @Requires(property = "spec.name", value = "access-gateway-runtime-context")
    static final class ConnectionRuntimeTestFactory {
        @Singleton
        EventBus eventBus() {
            return new InProcessEventBus();
        }

        @Singleton
        SessionResolver sessionResolver() {
            return sessionId -> Optional.of(new ResolvedSession(sessionId, 7L, 1L));
        }

        @Singleton
        SessionRouteWriter<io.netty.channel.Channel> sessionRouteWriter() {
            return SessionRouteWriter.noop();
        }

        @Singleton
        @Replaces(SslContext.class)
        SslContext sslContext() throws NoSuchAlgorithmException {
            return new StubSslContext();
        }
    }

    @Factory
    @Requires(property = "spec.name", value = "access-gateway-custom-directory")
    static final class CustomDirectoryFactory {
        @Singleton
        CustomDirectory customDirectory() {
            return new CustomDirectory();
        }
    }

    @Factory
    @Requires(property = "spec.name", value = "access-gateway-missing-route-writer")
    static final class MissingRouteWriterFactory {
        @Singleton
        SessionResolver sessionResolver() {
            return sessionId -> Optional.of(new ResolvedSession(sessionId, 11L, 1L));
        }
    }

    static final class CustomDirectory implements com.github.lystran.mochat.common.directory.UserChannelDirectory<io.netty.channel.Channel> {
        @Override
        public void bind(long userId, io.netty.channel.Channel channelRef) {
        }

        @Override
        public Optional<io.netty.channel.Channel> find(long userId) {
            return Optional.empty();
        }

        @Override
        public boolean unbind(long userId, io.netty.channel.Channel channelRef) {
            return false;
        }
    }

    private static final class StubSslContext extends SslContext {
        private final SSLContext delegate;

        private StubSslContext() throws NoSuchAlgorithmException {
            this.delegate = SSLContext.getDefault();
        }

        @Override
        public boolean isClient() {
            return false;
        }

        @Override
        public List<String> cipherSuites() {
            return List.of();
        }

        @Override
        public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
            return List::of;
        }

        @Override
        public SSLEngine newEngine(ByteBufAllocator byteBufAllocator) {
            SSLEngine sslEngine = delegate.createSSLEngine();
            sslEngine.setUseClientMode(false);
            return sslEngine;
        }

        @Override
        public SSLEngine newEngine(ByteBufAllocator byteBufAllocator, String peerHost, int peerPort) {
            SSLEngine sslEngine = delegate.createSSLEngine(peerHost, peerPort);
            sslEngine.setUseClientMode(false);
            return sslEngine;
        }

        @Override
        public SSLSessionContext sessionContext() {
            return delegate.getServerSessionContext();
        }
    }
}
