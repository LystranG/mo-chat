package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import com.github.lystran.mochat.runtime.config.AccessGatewayServiceConfiguration;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

@Singleton
@Context
@Requires(property = "micronaut.application.name", value = "access-gateway")
@Requires(property = "mochat.access-gateway.runtime.enabled", notEquals = "false", defaultValue = "true")
@Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
public final class AccessGatewayConnectionRuntimeLifecycle implements AutoCloseable {
    private final OutboundEventSubscriber outboundEventSubscriber;
    private final NettyChatServer nettyChatServer;
    private final AccessGatewayServiceConfiguration configuration;

    private boolean outboundSubscriberStarted;
    private boolean outboundSubscriberClosed;
    private boolean nettyServerStarted;

    public AccessGatewayConnectionRuntimeLifecycle(
        OutboundEventSubscriber outboundEventSubscriber,
        NettyChatServer nettyChatServer,
        AccessGatewayServiceConfiguration configuration
    ) {
        this.outboundEventSubscriber = outboundEventSubscriber;
        this.nettyChatServer = nettyChatServer;
        this.configuration = configuration;
    }

    @PostConstruct
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
