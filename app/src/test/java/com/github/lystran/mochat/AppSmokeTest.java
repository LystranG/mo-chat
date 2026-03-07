package com.github.lystran.mochat;

import io.micronaut.context.ApplicationContext;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSmokeTest {
    @Test
    void appContextBootstrapsBaseConfigAndTestBeans() {
        try (ApplicationContext context = ApplicationContext.run(AppTestSupport.runtimeAssemblyContextProperties())) {
            assertTrue(context.isRunning());
            assertEquals("mochat", context.getProperty("micronaut.application.name", String.class).orElseThrow());
            assertNotNull(context.getBean(SmokeSingleton.class));
        }
    }

    @Singleton
    static class SmokeSingleton {
    }
}
