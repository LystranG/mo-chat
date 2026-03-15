package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import com.github.lystran.mochat.runtime.config.AccessGatewayServiceConfiguration;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@Context
@Requires(property = "micronaut.application.name", value = "access-gateway")
@Requires(property = "mochat.access-gateway.runtime.enabled", notEquals = "false", defaultValue = "true")
@Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
/**
 * 管理接入网关里 Netty 服务和“给客户端发消息”的监听器的整体启停顺序。
 */
public final class AccessGatewayConnectionRuntimeLifecycle implements AutoCloseable {
    private final OutboundEventSubscriber outboundEventSubscriber;
    private final NettyChatServer nettyChatServer;
    private final AccessGatewayServiceConfiguration configuration;
    private final GatewayDrainManager gatewayDrainManager;

    private boolean outboundSubscriberStarted;
    private boolean outboundSubscriberClosed;
    private boolean nettyServerStarted;

    /**
     * 兼容不带 drain 管理器的创建方式。
     */
    public AccessGatewayConnectionRuntimeLifecycle(
        OutboundEventSubscriber outboundEventSubscriber,
        NettyChatServer nettyChatServer,
        AccessGatewayServiceConfiguration configuration
    ) {
        this(outboundEventSubscriber, nettyChatServer, configuration, null);
    }

    /**
     * 组装接入网关连接运行时需要的核心组件。
     */
    @Inject
    public AccessGatewayConnectionRuntimeLifecycle(
        OutboundEventSubscriber outboundEventSubscriber,
        NettyChatServer nettyChatServer,
        AccessGatewayServiceConfiguration configuration,
        GatewayDrainManager gatewayDrainManager
    ) {
        this.outboundEventSubscriber = outboundEventSubscriber;
        this.nettyChatServer = nettyChatServer;
        this.configuration = configuration;
        this.gatewayDrainManager = gatewayDrainManager;
    }

    @PostConstruct
    /**
     * 按“先开始监听要发给客户端的消息，再启动 TCP 服务”的顺序拉起运行时。
     */
    void start() {
        if (!configuration.getTcp().isEnabled()) {
            return;
        }

        try {
            outboundEventSubscriber.start();
            outboundSubscriberStarted = true;
            nettyChatServer.start();
            nettyServerStarted = true;
        } catch (InterruptedException interruptedException) {
            rollbackOutboundSubscriber(interruptedException);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to start Netty chat server", interruptedException);
        } catch (RuntimeException runtimeException) {
            rollbackOutboundSubscriber(runtimeException);
            throw runtimeException;
        }
    }

    @PreDestroy
    @Override
    /**
     * 关闭时先执行 drain，再停 Netty，最后停消息监听器。
     */
    public void close() {
        RuntimeException failure = null;

        if (nettyServerStarted) {
            if (gatewayDrainManager != null && configuration.getDrain().isShutdownWaitEnabled()) {
                try {
                    gatewayDrainManager.startDrain();
                    gatewayDrainManager.awaitDrainCompletion();
                } catch (RuntimeException runtimeException) {
                    failure = runtimeException;
                }
            }
            try {
                nettyChatServer.stop();
            } catch (RuntimeException runtimeException) {
                if (failure != null) {
                    failure.addSuppressed(runtimeException);
                } else {
                    failure = runtimeException;
                }
            }
        }

        RuntimeException closeFailure = closeOutboundSubscriber();
        if (closeFailure != null) {
            if (failure != null) {
                failure.addSuppressed(closeFailure);
            } else {
                failure = closeFailure;
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 启动失败时回滚已经启动的消息监听器。
     */
    private void rollbackOutboundSubscriber(Throwable startupFailure) {
        RuntimeException closeFailure = closeOutboundSubscriber();
        if (closeFailure != null) {
            startupFailure.addSuppressed(closeFailure);
        }
    }

    /**
     * 关闭消息监听器，并把异常统一折叠成运行时异常返回。
     */
    private RuntimeException closeOutboundSubscriber() {
        if (!outboundSubscriberStarted || outboundSubscriberClosed) {
            return null;
        }

        try {
            outboundEventSubscriber.close();
            outboundSubscriberClosed = true;
            return null;
        } catch (RuntimeException runtimeException) {
            return runtimeException;
        } catch (Exception exception) {
            return new IllegalStateException("Failed to stop outbound event subscriber", exception);
        }
    }
}
