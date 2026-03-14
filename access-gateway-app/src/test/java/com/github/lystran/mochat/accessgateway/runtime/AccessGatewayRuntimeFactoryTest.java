package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.runtime.config.AccessGatewayServiceConfiguration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessGatewayRuntimeFactoryTest {
    @Test
    void sessionResolutionExecutorUsesBoundedQueueAndAbortPolicy() {
        AccessGatewayRuntimeFactory factory = new AccessGatewayRuntimeFactory();
        AccessGatewayServiceConfiguration configuration = new AccessGatewayServiceConfiguration();
        configuration.getTcp().setSessionResolutionThreads(2);
        configuration.getTcp().setSessionResolutionQueueCapacity(3);

        try (ExecutorService executorService = factory.sessionResolutionExecutor(configuration)) {
            ThreadPoolExecutor executor = assertInstanceOf(ThreadPoolExecutor.class, executorService);

            assertEquals(2, executor.getCorePoolSize());
            assertEquals(2, executor.getMaximumPoolSize());
            assertEquals(3, executor.getQueue().remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, executor.getRejectedExecutionHandler());
        }
    }

    @Test
    void rejectsDisablingMandatoryTlsForChatTcp() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> AccessGatewayRuntimeFactory.buildMandatorySslContext(false, "", "", true)
        );

        assertEquals(
            "TLS is mandatory for chat TCP connections; mochat.access-gateway.tls.enabled=false is not supported",
            exception.getMessage()
        );
    }

    @Test
    void rejectsIncompleteExplicitTlsKeyPair() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> AccessGatewayRuntimeFactory.buildSslContext("/configured/tls.crt", "", false)
        );

        assertEquals(
            "TLS certificate-path and private-key-path must both be configured together",
            exception.getMessage()
        );
    }

    @Test
    void rejectsMissingExplicitCertificatesWhenSelfSignedTlsIsDisabled() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> AccessGatewayRuntimeFactory.buildSslContext("", "", false)
        );

        assertEquals(
            "TLS certificate-path and private-key-path are required when TLS is enabled and self-signed is disabled",
            exception.getMessage()
        );
    }
}
