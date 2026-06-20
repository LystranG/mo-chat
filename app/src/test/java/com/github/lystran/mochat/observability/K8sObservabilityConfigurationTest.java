package com.github.lystran.mochat.observability;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfiguration;
import io.micronaut.context.env.DefaultEnvironment;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class K8sObservabilityConfigurationTest {
    @Test
    void k8sEnvironmentEnablesPrometheusAndHealthEndpoints() {
        ApplicationContextBuilder builder = ApplicationContext.builder("k8s")
            .deduceEnvironment(false);
        try (Environment environment = new DefaultEnvironment((ApplicationContextConfiguration) builder)
            .start()) {
            assertTrue(environment.getActiveNames().contains("k8s"));
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("endpoints.all.enabled", Boolean.class).orElseThrow()
            );
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("endpoints.health.enabled", Boolean.class).orElseThrow()
            );
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("endpoints.prometheus.enabled", Boolean.class).orElseThrow()
            );
            assertEquals(
                "/prometheus",
                environment.getProperty("endpoints.prometheus.path", String.class).orElseThrow()
            );
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("micronaut.metrics.enabled", Boolean.class).orElseThrow()
            );
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("micronaut.metrics.export.prometheus.enabled", Boolean.class).orElseThrow()
            );
        }
    }
}
