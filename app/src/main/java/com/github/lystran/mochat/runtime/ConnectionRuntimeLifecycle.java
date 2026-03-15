package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/**
 * 在应用启动和关闭时，统一管理长连接入口和下行消息订阅器。
 */
@Singleton
@Context
public final class ConnectionRuntimeLifecycle implements AutoCloseable {
    private final OutboundEventSubscriber outboundEventSubscriber; // 负责把事件总线里的待发消息接过来，再推给在线客户端。
    private final NettyChatServer nettyChatServer; // 真正对外监听 TCP 长连接的聊天服务。
    private final boolean tcpEnabled; // 云原生拆分部署时，允许只跑服务间通信而不打开 TCP 端口。

    private boolean outboundSubscriberStarted; // 记录订阅器是否已经启动，方便启动失败时回滚。
    private boolean outboundSubscriberClosed; // 避免重复关闭同一个订阅器。
    private boolean nettyServerStarted; // 只有真正启动成功后，关闭阶段才会去停服务。

    /**
     * 组装长连接运行时需要的核心组件。
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
     * 先启动下行消息订阅器，再按开关决定是否拉起 TCP 服务。
     */
    @PostConstruct
    void start() {
        outboundEventSubscriber.start();
        outboundSubscriberStarted = true;
        if (!tcpEnabled) {
            // 关闭 TCP 后，这个进程仍然可能需要继续消费待发消息。
            return;
        }

        try {
            nettyChatServer.start();
            nettyServerStarted = true;
        } catch (InterruptedException interruptedException) {
            // 后面的 TCP 没起来时，要把前面已经打开的订阅器一起收掉，避免半启动状态。
            rollbackOutboundSubscriber(interruptedException);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to start Netty chat server", interruptedException);
        } catch (RuntimeException runtimeException) {
            // 运行时异常也按同样方式回滚，保证启动要么全成，要么全退。
            rollbackOutboundSubscriber(runtimeException);
            throw runtimeException;
        }
    }

    /**
     * 按与启动相反的顺序收掉 TCP 服务和下行消息订阅器。
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
     * 启动后半段失败时，把已经启动的订阅器补收掉。
     */
    private void rollbackOutboundSubscriber(Throwable startupFailure) {
        RuntimeException closeFailure = closeOutboundSubscriber();
        if (closeFailure != null) {
            startupFailure.addSuppressed(closeFailure);
        }
    }

    /**
     * 安全关闭下行消息订阅器，并把关闭异常整理成统一返回值。
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
