package com.github.lystran.mochat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSmokeTest {
    @Test
    void appBootstrapsConfigAndBeans() {
        try (ApplicationContext context = Micronaut.run(Application.class)) {
            assertTrue(context.isRunning());
            assertEquals("mochat", context.getProperty("micronaut.application.name", String.class).orElseThrow());
            assertNotNull(context.getBean(SmokeSingleton.class));
        }
    }

    @Singleton
    static class SmokeSingleton {
    }
}
