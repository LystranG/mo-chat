package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/**
 * 负责在应用启动和关闭时管理连接层：先接住要发给客户端的数据，再启动或停止聊天 TCP 服务。
 */
@Singleton
@Context
public final class ConnectionRuntimeLifecycle implements AutoCloseable {
    private final OutboundEventSubscriber outboundEventSubscriber;
    private final NettyChatServer nettyChatServer;
    private final boolean tcpEnabled;

    private boolean outboundSubscriberStarted;
    private boolean outboundSubscriberClosed;
    private boolean nettyServerStarted;

    /**
     * 收下启动连接层时要用到的组件和开关。
     */
    public ConnectionRuntimeLifecycle(
        OutboundEventSubscriber outboundEventSubscriber,
        NettyChatServer nettyChatServer,
        @Property(name = "mochat.netty.tcp.enabled", defaultValue = "true") boolean tcpEnabled
    ) {
        this.outboundEventSubscriber = outboundEventSubscriber;
        this.nettyChatServer = nettyChatServer;
        this.tcpEnabled = tcpEnabled;
    }

    /**
     * 应用启动时先订阅连接层要发出去的数据，再按配置决定是否打开聊天 TCP 端口。
     */
    @PostConstruct
    void start() {
        // 应用一启动就先把“要发给客户端的数据”接进来；如果后面 TCP 服务没起来，
        // 就把这一步撤掉，避免出现“内部还在发消息，但外部端口根本没开”的半成品状态。
        outboundEventSubscriber.start();
        outboundSubscriberStarted = true;
        if (!tcpEnabled) {
            return;
        }

        try {
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

    /**
     * 应用关闭时按顺序先停 TCP 服务，再取消前面建立的出站事件订阅。
     */
    @PreDestroy
    @Override
    public void close() {
        RuntimeException failure = null;

        if (nettyServerStarted) {
            try {
                nettyChatServer.stop();
            } catch (RuntimeException runtimeException) {
                failure = runtimeException;
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
     * 启动中途出错时，把前面已经建好的出站事件订阅撤掉。
     */
    private void rollbackOutboundSubscriber(Throwable startupFailure) {
        RuntimeException closeFailure = closeOutboundSubscriber();
        if (closeFailure != null) {
            startupFailure.addSuppressed(closeFailure);
        }
    }

    /**
     * 关闭出站事件订阅，并把关闭时抛出的异常统一整理成运行时异常。
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
