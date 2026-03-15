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
import com.github.lystran.mochat.runtime.topology.GatewayAddressResolver;
import com.github.lystran.mochat.runtime.topology.GatewayDiscoveryMode;
import com.github.lystran.mochat.runtime.topology.GatewayIdentityMode;
import com.github.lystran.mochat.runtime.topology.GatewayIdentityProvider;
import com.github.lystran.mochat.runtime.topology.KubernetesDnsGatewayAddressResolver;
import com.github.lystran.mochat.runtime.topology.PodMetadataGatewayIdentityProvider;
import com.github.lystran.mochat.runtime.topology.RuntimeTopologyConfiguration;
import com.github.lystran.mochat.runtime.topology.StaticGatewayAddressResolver;
import com.github.lystran.mochat.runtime.topology.StaticGatewayIdentityProvider;
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
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Factory
@Requires(property = "micronaut.application.name", value = "access-gateway")
/**
 * 组装 access-gateway 运行时所需的连接、路由、TLS 和 Netty 相关 Bean。
 */
public final class AccessGatewayRuntimeFactory {
    @Singleton
    @Bean(preDestroy = "shutdown")
    @Requires(property = "mochat.access-gateway.dependencies.redis-enabled", notEquals = "false", defaultValue = "true")
    /**
     * 创建 Redis 客户端。
     */
    RedisClient redisClient(@Property(name = "mochat.redis.uri") String redisUri) {
        return RedisClient.create(redisUri);
    }

    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    /**
     * 创建普通 Redis 连接，供同步命令使用。
     */
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    /**
     * 创建 Redis Pub/Sub 连接，供事件总线订阅使用。
     */
    StatefulRedisPubSubConnection<String, String> redisPubSubConnection(RedisClient redisClient) {
        return redisClient.connectPubSub();
    }

    @Singleton
    @Requires(bean = StatefulRedisConnection.class)
    /**
     * 暴露同步 Redis 命令对象。
     */
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }

    @Singleton
    @Requires(missingBeans = EventBus.class)
    @Requires(bean = RedisCommands.class)
    @Requires(bean = StatefulRedisPubSubConnection.class)
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    /**
     * 默认使用 Redis 作为网关之间共享的事件总线。
     */
    EventBus eventBus(
        RedisCommands<String, String> redisCommands,
        StatefulRedisPubSubConnection<String, String> redisPubSubConnection
    ) {
        return new RedisEventBus(redisCommands, redisPubSubConnection);
    }

    @Singleton
    @Requires(missingBeans = OfflineQueue.class)
    /**
     * 默认提供一个空实现，避免网关在未配置离线队列时启动失败。
     */
    OfflineQueue offlineQueue() {
        return new NoOpOfflineQueue();
    }

    @Singleton
    @Requires(missingBeans = UserChannelDirectory.class)
    /**
     * 提供当前进程内的连接目录。
     */
    InMemoryUserChannelDirectory userChannelDirectory() {
        return new InMemoryUserChannelDirectory();
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    /**
     * 决定当前网关写进在线路由里的实例名字。
     */
    GatewayIdentityProvider gatewayIdentityProvider(
        RuntimeTopologyConfiguration runtimeTopologyConfiguration,
        @Property(name = "mochat.access-gateway.route.gateway-pod", defaultValue = "access-gateway-local") String legacyGatewayPod
    ) {
        String fallbackGatewayPod = runtimeTopologyConfiguration.getGateway().getIdentityValue();
        if (fallbackGatewayPod == null || fallbackGatewayPod.isBlank() || "access-gateway-local".equals(fallbackGatewayPod)) {
            fallbackGatewayPod = legacyGatewayPod;
        }
        if (runtimeTopologyConfiguration.getGateway().getIdentityMode() == GatewayIdentityMode.POD_METADATA) {
            String podName = runtimeTopologyConfiguration.getPod().getName();
            if (podName != null && !podName.isBlank()) {
                // 在 Kubernetes 里优先用稳定 Pod 名，这样别的服务才能精确找到真正持有连接的实例。
                return new PodMetadataGatewayIdentityProvider(podName);
            }
        }
        return new StaticGatewayIdentityProvider(fallbackGatewayPod);
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    /**
     * 根据部署方式把“网关实例名字”换算成可访问的 gRPC 地址。
     */
    GatewayAddressResolver gatewayAddressResolver(
        RuntimeTopologyConfiguration runtimeTopologyConfiguration,
        AccessGatewayServiceConfiguration accessGatewayServiceConfiguration
    ) {
        RuntimeTopologyConfiguration.Gateway gateway = runtimeTopologyConfiguration.getGateway();
        GatewayDiscoveryMode discoveryMode = gateway.getDiscoveryMode();
        if (discoveryMode == GatewayDiscoveryMode.AUTO) {
            String podName = runtimeTopologyConfiguration.getPod().getName();
            discoveryMode = (podName == null || podName.isBlank())
                ? GatewayDiscoveryMode.STATIC_MAP
                : GatewayDiscoveryMode.KUBERNETES_DNS;
        }
        if (discoveryMode == GatewayDiscoveryMode.KUBERNETES_DNS) {
            String namespace = gateway.getNamespace();
            if (namespace == null || namespace.isBlank()) {
                namespace = runtimeTopologyConfiguration.getPod().getNamespace();
            }
            return new KubernetesDnsGatewayAddressResolver(
                gateway.getHeadlessService(),
                namespace,
                gateway.getClusterDomain(),
                gateway.getGrpcPort()
            );
        }
        Map<String, String> staticTargets = gateway.getStaticTargets().isEmpty()
            ? accessGatewayServiceConfiguration.getRoute().getPeerTargets()
            : gateway.getStaticTargets();
        return new StaticGatewayAddressResolver(staticTargets);
    }

    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    /**
     * 创建在线路由写入器，把“这个网关现在负责这个用户连接”写进 Redis。
     */
    SessionRouteWriter<Channel> redisOnlineRouteWriter(
        RedisCommands<String, String> redisCommands,
        AccessGatewayServiceConfiguration configuration,
        InMemoryUserChannelDirectory userChannelDirectory,
        GatewayIdentityProvider gatewayIdentityProvider
    ) {
        return new RedisOnlineRouteChannelSessionRegistry(
            redisCommands,
            gatewayIdentityProvider.currentGatewayPod(),
            configuration.getTcp().getHeartbeatTimeout(),
            userChannelDirectory
        );
    }

    @Singleton
    @Requires(missingBeans = ChannelSessionRegistry.class)
    /**
     * 创建本地会话绑定目录。
     */
    ChannelSessionRegistry<Channel> channelSessionRegistry(InMemoryUserChannelDirectory userChannelDirectory) {
        return new InMemoryChannelSessionRegistry<>(userChannelDirectory);
    }

    @Singleton
    @Requires(missingBeans = SessionResolver.class)
    @Requires(bean = SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class)
    /**
     * 默认使用 gRPC 到 api-service 查询会话权威结果。
     */
    SessionResolver sessionResolver(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub) {
        return new GrpcSessionResolver(stub);
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    /**
     * 构造聊天 TCP 所需的 TLS 上下文。
     */
    SslContext sslContext(
        @Property(name = "mochat.access-gateway.tls.enabled", defaultValue = "true") boolean tlsEnabled,
        @Property(name = "mochat.access-gateway.tls.certificate-path", defaultValue = "") String certificatePath,
        @Property(name = "mochat.access-gateway.tls.private-key-path", defaultValue = "") String privateKeyPath,
        @Property(name = "mochat.access-gateway.tls.self-signed", defaultValue = "true") boolean selfSigned
    ) {
        try {
            return buildMandatorySslContext(tlsEnabled, certificatePath, privateKeyPath, selfSigned);
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
    /**
     * 为会话解析和异步绑定准备专用线程池，避免阻塞 Netty 事件循环。
     */
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
    /**
     * 为网关 drain 宽限期准备单线程调度器。
     */
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
    /**
     * 创建网关退场管理器，必要时支持启动即进入 drain。
     */
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
    /**
     * 创建旧连接替换处理器，负责把被新绑定顶掉的旧连接关掉。
     */
    SessionReplacementHandler sessionReplacementHandler(
        InMemoryUserChannelDirectory userChannelDirectory,
        AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory,
        GatewayIdentityProvider gatewayIdentityProvider,
        GatewayAddressResolver gatewayAddressResolver
    ) {
        return new GatewayRouteReplacementHandler(
            gatewayIdentityProvider.currentGatewayPod(),
            userChannelDirectory,
            accessGatewayDispatchClientFactory,
            gatewayAddressResolver
        );
    }

    @Singleton
    @Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
    /**
     * 创建会话绑定处理器，统一负责认人、写路由和处理 drain。
     */
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
    /**
     * 创建聊天连接的 Netty 管线初始化器。
     */
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
    /**
     * 创建真正监听 TCP 端口的 Netty 服务。
     */
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
    /**
     * 创建消息下发监听器，把需要推给客户端的消息写回本地连接。
     */
    OutboundEventSubscriber outboundEventSubscriber(
        EventBus eventBus,
        UserChannelDirectory<Channel> userChannelDirectory,
        OfflineQueue offlineQueue
    ) {
        return new OutboundEventSubscriber(eventBus, userChannelDirectory, offlineQueue);
    }

    /**
     * 强制要求聊天 TCP 一定开启 TLS。
     */
    static SslContext buildMandatorySslContext(
        boolean tlsEnabled,
        String certificatePath,
        String privateKeyPath,
        boolean selfSigned
    ) throws Exception {
        if (!tlsEnabled) {
            throw new IllegalStateException(
                "TLS is mandatory for chat TCP connections; mochat.access-gateway.tls.enabled=false is not supported"
            );
        }
        return buildSslContext(certificatePath, privateKeyPath, selfSigned);
    }

    /**
     * 根据显式证书或自签证书配置构造 TLS 上下文。
     */
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

    /**
     * 没有离线能力时使用的空实现，占位但不真正保存消息。
     */
    static final class NoOpOfflineQueue implements OfflineQueue {
        @Override
        /**
         * 忽略离线入队请求。
         */
        public void enqueue(long userId, String payload, int maxQueueSize) {
        }

        @Override
        /**
         * 返回空结果，表示这里没有任何离线消息可取。
         */
        public java.util.List<String> drain(long userId, int maxItems) {
            return java.util.List.of();
        }
    }
}
