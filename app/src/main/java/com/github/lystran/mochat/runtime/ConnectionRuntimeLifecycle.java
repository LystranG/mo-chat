package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

@Singleton
@Context
public final class ConnectionRuntimeLifecycle implements AutoCloseable {
    private final OutboundEventSubscriber outboundEventSubscriber;
    private final NettyChatServer nettyChatServer;
    private final boolean tcpEnabled;

    private boolean outboundSubscriberStarted;
    private boolean outboundSubscriberClosed;
    private boolean nettyServerStarted;

    public ConnectionRuntimeLifecycle(
        OutboundEventSubscriber outboundEventSubscriber,
        NettyChatServer nettyChatServer,
        @Property(name = "mochat.netty.tcp.enabled", defaultValue = "true") boolean tcpEnabled
    ) {
        this.outboundEventSubscriber = outboundEventSubscriber;
        this.nettyChatServer = nettyChatServer;
        this.tcpEnabled = tcpEnabled;
    }

    @PostConstruct
    void start() {
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

    private void rollbackOutboundSubscriber(Throwable startupFailure) {
        RuntimeException closeFailure = closeOutboundSubscriber();
        if (closeFailure != null) {
            startupFailure.addSuppressed(closeFailure);
        }
    }

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
