package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.runtime.config.AccessGatewayServiceConfiguration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
}
