package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.session.ChannelSessionRegistry;
import com.github.lystran.mochat.common.session.InMemoryChannelSessionRegistry;
import com.github.lystran.mochat.common.session.SessionReplacementHandler;
import com.github.lystran.mochat.common.session.SessionRouteWriter;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.connection.ChatChannelInitializer;
import com.github.lystran.mochat.connection.GatewayDrainState;
import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import com.github.lystran.mochat.infra.redis.RedisEventBus;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.runtime.config.AccessGatewayServiceConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import jakarta.inject.Singleton;
import jakarta.inject.Named;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Factory
@Requires(property = "micronaut.application.name", value = "access-gateway")
public final class AccessGatewayRuntimeFactory {
    @Singleton
    @Bean(preDestroy = "shutdown")
    @Requires(property = "mochat.access-gateway.dependencies.redis-enabled", notEquals = "false", defaultValue = "true")
    RedisClient redisClient(@Property(name = "mochat.redis.uri") String redisUri) {
        return RedisClient.create(redisUri);
    }

    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    StatefulRedisPubSubConnection<String, String> redisPubSubConnection(RedisClient redisClient) {
        return redisClient.connectPubSub();
    }

    @Singleton
    @Requires(bean = StatefulRedisConnection.class)
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }

    @Singleton
    @Requires(missingBeans = EventBus.class)
    @Requires(bean = RedisCommands.class)
    @Requires(bean = StatefulRedisPubSubConnection.class)
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    EventBus eventBus(
        RedisCommands<String, String> redisCommands,
        StatefulRedisPubSubConnection<String, String> redisPubSubConnection
    ) {
        return new RedisEventBus(redisCommands, redisPubSubConnection);
    }

    @Singleton
    @Requires(missingBeans = OfflineQueue.class)
    OfflineQueue offlineQueue() {
        return new NoOpOfflineQueue();
    }

    @Singleton
    @Requires(missingBeans = UserChannelDirectory.class)
    InMemoryUserChannelDirectory userChannelDirectory() {
        return new InMemoryUserChannelDirectory();
    }

    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    SessionRouteWriter<Channel> redisOnlineRouteWriter(
        RedisCommands<String, String> redisCommands,
        AccessGatewayServiceConfiguration configuration,
        InMemoryUserChannelDirectory userChannelDirectory,
        @Property(name = "mochat.access-gateway.route.gateway-pod", defaultValue = "access-gateway-local") String gatewayPod
    ) {
        return new RedisOnlineRouteChannelSessionRegistry(
            redisCommands,
            gatewayPod,
            configuration.getTcp().getHeartbeatTimeout(),
            userChannelDirectory
        );
    }

    @Singleton
    @Requires(missingBeans = ChannelSessionRegistry.class)
    ChannelSessionRegistry<Channel> channelSessionRegistry(InMemoryUserChannelDirectory userChannelDirectory) {
        return new InMemoryChannelSessionRegistry<>(userChannelDirectory);
    }

    @Singleton
    @Requires(missingBeans = SessionResolver.class)
    @Requires(bean = SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class)
    SessionResolver sessionResolver(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub) {
        return new GrpcSessionResolver(stub);
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    SslContext sslContext(AccessGatewayServiceConfiguration configuration) {
        try {
            return buildMandatorySslContext(configuration.getTls());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build TLS context", exception);
        }
    }

    @Singleton
    @Bean(preDestroy = "shutdown")
    @Named("accessGatewaySessionResolutionExecutor")
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    ExecutorService sessionResolutionExecutor(AccessGatewayServiceConfiguration configuration) {
        int threads = Math.max(1, configuration.getTcp().getSessionResolutionThreads());
        int queueCapacity = Math.max(1, configuration.getTcp().getSessionResolutionQueueCapacity());
        return new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Singleton
    @Bean(preDestroy = "shutdown")
    @Named("accessGatewayDrainScheduler")
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    ScheduledExecutorService drainScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "access-gateway-drain");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Singleton
    @Bean(preDestroy = "close")
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    GatewayDrainManager gatewayDrainManager(
        InMemoryUserChannelDirectory userChannelDirectory,
        @Named("accessGatewayDrainScheduler") ScheduledExecutorService drainScheduler,
        @Property(name = "mochat.access-gateway.drain.enabled", defaultValue = "false") boolean drainEnabled,
        @Property(name = "mochat.access-gateway.drain.grace-period", defaultValue = "30s") Duration drainGracePeriod
    ) {
        GatewayDrainManager gatewayDrainManager = new GatewayDrainManager(
            userChannelDirectory,
            drainScheduler,
            drainGracePeriod
        );
        if (drainEnabled) {
            gatewayDrainManager.startDrain();
        }
        return gatewayDrainManager;
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    SessionReplacementHandler sessionReplacementHandler(
        InMemoryUserChannelDirectory userChannelDirectory,
        AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory,
        AccessGatewayServiceConfiguration configuration,
        @Property(name = "mochat.access-gateway.route.gateway-pod", defaultValue = "access-gateway-local") String gatewayPod
    ) {
        return new GatewayRouteReplacementHandler(
            gatewayPod,
            userChannelDirectory,
            accessGatewayDispatchClientFactory,
            configuration.getRoute().getPeerTargets()
        );
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    SessionBindingHandler sessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry,
        SessionRouteWriter<Channel> sessionRouteWriter,
        SessionReplacementHandler sessionReplacementHandler,
        GatewayDrainState gatewayDrainState,
        @Named("accessGatewaySessionResolutionExecutor") ExecutorService sessionResolutionExecutor,
        AccessGatewayServiceConfiguration configuration
    ) {
        return new SessionBindingHandler(
            sessionResolver,
            channelSessionRegistry,
            sessionRouteWriter,
            sessionReplacementHandler,
            gatewayDrainState,
            sessionResolutionExecutor,
            configuration.getTcp().getSessionResolutionPendingLimit()
        );
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    ChatChannelInitializer chatChannelInitializer(
        EventBus eventBus,
        SslContext sslContext,
        SessionBindingHandler sessionBindingHandler,
        AccessGatewayServiceConfiguration configuration
    ) {
        int heartbeatIntervalSeconds = (int) Math.max(1L, configuration.getTcp().getHeartbeatInterval().getSeconds());
        int heartbeatTimeoutSeconds = (int) Math.max(1L, configuration.getTcp().getHeartbeatTimeout().getSeconds());
        return new ChatChannelInitializer(
            eventBus,
            sslContext,
            sessionBindingHandler,
            configuration.getTcp().getFrameMaxLength(),
            heartbeatIntervalSeconds,
            heartbeatTimeoutSeconds
        );
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    NettyChatServer nettyChatServer(
        ChatChannelInitializer chatChannelInitializer,
        AccessGatewayServiceConfiguration configuration
    ) {
        return new NettyChatServer(
            configuration.getTcp().getHost(),
            configuration.getTcp().getPort(),
            chatChannelInitializer
        );
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    OutboundEventSubscriber outboundEventSubscriber(
        EventBus eventBus,
        UserChannelDirectory<Channel> userChannelDirectory,
        OfflineQueue offlineQueue
    ) {
        return new OutboundEventSubscriber(eventBus, userChannelDirectory, offlineQueue);
    }

    static SslContext buildMandatorySslContext(AccessGatewayServiceConfiguration.Tls tls) throws Exception {
        if (!tls.isEnabled()) {
            throw new IllegalStateException("TLS is mandatory for chat TCP connections; mochat.access-gateway.tls.enabled=false is not supported");
        }
        return buildSslContext(tls.getCertificatePath(), tls.getPrivateKeyPath(), tls.isSelfSigned());
    }

    static SslContext buildSslContext(String certificatePath, String privateKeyPath, boolean selfSigned) throws Exception {
        boolean hasCertificatePath = certificatePath != null && !certificatePath.isBlank();
        boolean hasPrivateKeyPath = privateKeyPath != null && !privateKeyPath.isBlank();
        if (hasCertificatePath != hasPrivateKeyPath) {
            throw new IllegalStateException("TLS certificate-path and private-key-path must both be configured together");
        }
        if (hasCertificatePath) {
            return NettyChatServer.buildTls13Context(new File(certificatePath), new File(privateKeyPath));
        }
        if (!selfSigned) {
            throw new IllegalStateException(
                "TLS certificate-path and private-key-path are required when TLS is enabled and self-signed is disabled"
            );
        }

        SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate("localhost");
        return NettyChatServer.buildTls13Context(selfSignedCertificate.certificate(), selfSignedCertificate.privateKey());
    }

    static final class NoOpOfflineQueue implements OfflineQueue {
        @Override
        public void enqueue(long userId, String payload, int maxQueueSize) {
        }

        @Override
        public java.util.List<String> drain(long userId, int maxItems) {
            return java.util.List.of();
        }
    }
}
