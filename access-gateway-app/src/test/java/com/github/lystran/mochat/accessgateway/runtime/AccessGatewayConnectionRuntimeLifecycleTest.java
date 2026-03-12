package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.connection.ChatChannelInitializer;
import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import com.github.lystran.mochat.runtime.config.AccessGatewayServiceConfiguration;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessGatewayConnectionRuntimeLifecycleTest {
    @Test
    void tcpDisabledSkipsOutboundSubscriberAndNettyStartup() {
        RecordingEventBus eventBus = new RecordingEventBus();
        OutboundEventSubscriber outboundEventSubscriber = new OutboundEventSubscriber(
            eventBus,
            new InMemoryDirectory(),
            new NoOpOfflineQueue()
        );
        AccessGatewayServiceConfiguration configuration = configuration(false);
        AtomicInteger startAttempts = new AtomicInteger();
        AccessGatewayConnectionRuntimeLifecycle lifecycle = new AccessGatewayConnectionRuntimeLifecycle(
            outboundEventSubscriber,
            recordingNettyChatServer(startAttempts),
            configuration
        );

        lifecycle.start();
        lifecycle.close();

        assertEquals(0, startAttempts.get());
        assertEquals(0, eventBus.activeSubscriptionCount());
        assertEquals(0, eventBus.closedSubscriptionCount());
    }

    @Test
    void startupFailureRollsBackOutboundSubscriber() {
        RecordingEventBus eventBus = new RecordingEventBus();
        OutboundEventSubscriber outboundEventSubscriber = new OutboundEventSubscriber(
            eventBus,
            new InMemoryDirectory(),
            new NoOpOfflineQueue()
        );
        AccessGatewayConnectionRuntimeLifecycle lifecycle = new AccessGatewayConnectionRuntimeLifecycle(
            outboundEventSubscriber,
            failingNettyChatServer(new IllegalStateException("netty startup failed")),
            configuration(true)
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, lifecycle::start);

        assertEquals("netty startup failed", exception.getMessage());
        assertEquals(1, eventBus.closedSubscriptionCount());
        assertEquals(0, eventBus.activeSubscriptionCount());
    }

    private static AccessGatewayServiceConfiguration configuration(boolean tcpEnabled) {
        AccessGatewayServiceConfiguration configuration = new AccessGatewayServiceConfiguration();
        configuration.getTcp().setEnabled(tcpEnabled);
        return configuration;
    }

    private static NettyChatServer recordingNettyChatServer(AtomicInteger startAttempts) {
        try {
            Class<?> transportSelectorType = Class.forName("com.github.lystran.mochat.connection.NettyChatServer$TransportSelector");
            Class<?> serverBinderType = Class.forName("com.github.lystran.mochat.connection.NettyChatServer$ServerBinder");
            Class<?> transportSelectionType = Class.forName("com.github.lystran.mochat.connection.NettyChatServer$TransportSelection");

            Constructor<?> transportSelectionConstructor = transportSelectionType.getDeclaredConstructor(
                EventLoopGroup.class,
                EventLoopGroup.class,
                Class.class,
                boolean.class
            );
            transportSelectionConstructor.setAccessible(true);

            Object transportSelection = transportSelectionConstructor.newInstance(
                new NioEventLoopGroup(1),
                new NioEventLoopGroup(1),
                NioServerSocketChannel.class,
                false
            );

            Object transportSelector = Proxy.newProxyInstance(
                transportSelectorType.getClassLoader(),
                new Class<?>[]{transportSelectorType},
                (proxy, method, args) -> transportSelection
            );
            Object serverBinder = Proxy.newProxyInstance(
                serverBinderType.getClassLoader(),
                new Class<?>[]{serverBinderType},
                (proxy, method, args) -> {
                    startAttempts.incrementAndGet();
                    return new io.netty.channel.embedded.EmbeddedChannel();
                }
            );

            Constructor<NettyChatServer> constructor = NettyChatServer.class.getDeclaredConstructor(
                String.class,
                int.class,
                ChatChannelInitializer.class,
                transportSelectorType,
                serverBinderType
            );
            constructor.setAccessible(true);
            return constructor.newInstance(
                "127.0.0.1",
                9000,
                new ChatChannelInitializer(new RecordingEventBus(), null),
                transportSelector,
                serverBinder
            );
        } catch (ReflectiveOperationException reflectionException) {
            throw new IllegalStateException("Failed to build test NettyChatServer", reflectionException);
        }
    }

    private static NettyChatServer failingNettyChatServer(RuntimeException startupFailure) {
        try {
            Class<?> transportSelectorType = Class.forName("com.github.lystran.mochat.connection.NettyChatServer$TransportSelector");
            Class<?> serverBinderType = Class.forName("com.github.lystran.mochat.connection.NettyChatServer$ServerBinder");
            Class<?> transportSelectionType = Class.forName("com.github.lystran.mochat.connection.NettyChatServer$TransportSelection");

            Constructor<?> transportSelectionConstructor = transportSelectionType.getDeclaredConstructor(
                EventLoopGroup.class,
                EventLoopGroup.class,
                Class.class,
                boolean.class
            );
            transportSelectionConstructor.setAccessible(true);

            Object transportSelection = transportSelectionConstructor.newInstance(
                new NioEventLoopGroup(1),
                new NioEventLoopGroup(1),
                NioServerSocketChannel.class,
                false
            );

            Object transportSelector = Proxy.newProxyInstance(
                transportSelectorType.getClassLoader(),
                new Class<?>[]{transportSelectorType},
                (proxy, method, args) -> transportSelection
            );
            Object serverBinder = Proxy.newProxyInstance(
                serverBinderType.getClassLoader(),
                new Class<?>[]{serverBinderType},
                (proxy, method, args) -> {
                    throw startupFailure;
                }
            );

            Constructor<NettyChatServer> constructor = NettyChatServer.class.getDeclaredConstructor(
                String.class,
                int.class,
                ChatChannelInitializer.class,
                transportSelectorType,
                serverBinderType
            );
            constructor.setAccessible(true);
            return constructor.newInstance(
                "127.0.0.1",
                9000,
                new ChatChannelInitializer(new RecordingEventBus(), null),
                transportSelector,
                serverBinder
            );
        } catch (ReflectiveOperationException reflectionException) {
            throw new IllegalStateException("Failed to build test NettyChatServer", reflectionException);
        }
    }

    private static final class RecordingEventBus implements EventBus {
        private final AtomicInteger activeSubscriptionCount = new AtomicInteger();
        private final AtomicInteger closedSubscriptionCount = new AtomicInteger();

        @Override
        public void publish(String topic, String event) {
        }

        @Override
        public AutoCloseable subscribe(String topic, java.util.function.Consumer<String> subscriber) {
            activeSubscriptionCount.incrementAndGet();
            return () -> {
                activeSubscriptionCount.decrementAndGet();
                closedSubscriptionCount.incrementAndGet();
            };
        }

        int activeSubscriptionCount() {
            return activeSubscriptionCount.get();
        }

        int closedSubscriptionCount() {
            return closedSubscriptionCount.get();
        }
    }

    private static final class InMemoryDirectory implements UserChannelDirectory<Channel> {
        private final ConcurrentHashMap<Long, Channel> channels = new ConcurrentHashMap<>();

        @Override
        public void bind(long userId, Channel channelRef) {
            channels.put(userId, channelRef);
        }

        @Override
        public Optional<Channel> find(long userId) {
            return Optional.ofNullable(channels.get(userId));
        }

        @Override
        public boolean unbind(long userId, Channel channelRef) {
            return channels.remove(userId, channelRef);
        }
    }

    private static final class NoOpOfflineQueue implements OfflineQueue {
        @Override
        public void enqueue(long userId, String payload, int maxQueueSize) {
        }

        @Override
        public List<String> drain(long userId, int maxItems) {
            return List.of();
        }
    }
}
