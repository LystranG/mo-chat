package com.github.lystran.mochat.runtime.kubernetes;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HelmMoChatChartContractTest {
    @Test
    void helmTemplateRendersFiveMochatServices() throws Exception {
        List<Map<String, Object>> manifests = renderChart();

        assertHasManifest(manifests, "Deployment", "api-service");
        assertHasManifest(manifests, "Deployment", "message-service");
        assertHasManifest(manifests, "Deployment", "persistence-service");
        assertHasManifest(manifests, "StatefulSet", "access-gateway");
        assertHasManifest(manifests, "Deployment", "call-service");

        assertHasManifest(manifests, "Service", "api-service");
        assertHasManifest(manifests, "Service", "message-service");
        assertHasManifest(manifests, "Service", "access-gateway-headless");
        assertHasManifest(manifests, "Service", "access-gateway-tcp");
        assertHasManifest(manifests, "Service", "call-service");
    }

    @Test
    void callServiceUsesSingleReplicaLivekitSecretAndHttpPort8090() throws Exception {
        List<Map<String, Object>> manifests = renderChart();
        Map<String, Object> deployment = manifest(manifests, "Deployment", "call-service");

        assertEquals(1, nestedMap(deployment, "spec").get("replicas"));
        Map<String, Object> container = firstContainer(deployment);
        assertHasContainerPort(container, "http", 8090);
        assertEnvFromSecret(container, "mochat-livekit");
        assertEnvFromSecret(container, "mochat-external-dependency-secrets");

        Map<String, Object> service = manifest(manifests, "Service", "call-service");
        assertServiceType(service, "NodePort");
        assertHasServicePort(service, "http", 8090);
        assertHasServiceNodePort(service, "http", 32090);
    }

    @Test
    void devValuesKeepCallServiceInternalOnly() throws Exception {
        List<Map<String, Object>> manifests = renderChartWithValues("values-dev.yaml");
        Map<String, Object> service = manifest(manifests, "Service", "call-service");

        assertServiceType(service, "ClusterIP");
        assertHasServicePort(service, "http", 8090);
    }

    @Test
    void allWorkloadsExposeAiopsCorrelationLabels() throws Exception {
        List<Map<String, Object>> manifests = renderChart();
        for (String name : List.of("api-service", "message-service", "persistence-service", "call-service")) {
            assertWorkloadLabels(manifest(manifests, "Deployment", name), name);
        }
        assertWorkloadLabels(manifest(manifests, "StatefulSet", "access-gateway"), "access-gateway");
    }

    @Test
    void prometheusAnnotationsAreRenderedForHttpScrapeTargets() throws Exception {
        List<Map<String, Object>> manifests = renderChart("--set", "observability.prometheus.scrape=true");
        assertPrometheusAnnotation(manifest(manifests, "Deployment", "api-service"), "8080");
        assertPrometheusAnnotation(manifest(manifests, "StatefulSet", "access-gateway"), "18080");
        assertPrometheusAnnotation(manifest(manifests, "Deployment", "call-service"), "8090");
    }

    @Test
    void otelEndpointIsOnlyRenderedWhenEnabled() throws Exception {
        List<Map<String, Object>> disabledManifests = renderChart();
        Map<String, Object> disabledData = nestedMap(manifest(disabledManifests, "ConfigMap", "mochat-observability"), "data");
        assertTrue(!disabledData.containsKey("OTEL_EXPORTER_OTLP_ENDPOINT"));

        List<Map<String, Object>> enabledManifests = renderChart("--set", "observability.otel.enabled=true");
        Map<String, Object> enabledData = nestedMap(manifest(enabledManifests, "ConfigMap", "mochat-observability"), "data");
        assertEquals(
            "http://tempo.mochat-observability.svc.cluster.local:4318",
            enabledData.get("OTEL_EXPORTER_OTLP_ENDPOINT")
        );
        assertTrue(enabledData.containsKey("OTEL_RESOURCE_ATTRIBUTES"));
    }

    @Test
    void localValuesDoNotRenderNamespaceWhenHelmCreateNamespaceIsUsed() throws Exception {
        List<Map<String, Object>> manifests = renderChart();

        assertNoManifest(manifests, "Namespace", "mochat");
    }

    @Test
    void devValuesRenderK3sDevelopmentEnvironmentLabelsAndRuntimeConfig() throws Exception {
        List<Map<String, Object>> manifests = renderChartWithValues("values-dev.yaml");

        Map<String, Object> apiDeployment = manifest(manifests, "Deployment", "api-service");
        Map<String, Object> labels = nestedMap(apiDeployment, "spec", "template", "metadata", "labels");
        assertEquals("mochat-dev", labels.get("mochat.lystran.io/project-id"));
        assertEquals("dev", labels.get("mochat.lystran.io/environment"));

        Map<String, Object> runtimeConfig = manifest(manifests, "ConfigMap", "mochat-runtime-config");
        Map<String, Object> runtimeData = nestedMap(runtimeConfig, "data");
        assertEquals("api-service:19091", runtimeData.get("MOCHAT_API_SERVICE_GRPC_ADDRESS"));
        assertEquals("message-service:19092", runtimeData.get("MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS"));
        assertEquals("access-gateway-headless", runtimeData.get("MOCHAT_GATEWAY_HEADLESS_SERVICE"));

        Map<String, Object> gateway = manifest(manifests, "StatefulSet", "access-gateway");
        Map<String, Object> gatewayContainer = firstContainer(gateway);
        List<Map<String, Object>> env = nestedList(gatewayContainer, "env");
        assertTrue(env.stream().anyMatch(entry -> Objects.equals("MOCHAT_RUNTIME_POD_NAME", entry.get("name"))));
        assertTrue(env.stream().anyMatch(entry -> Objects.equals("MOCHAT_RUNTIME_POD_NAMESPACE", entry.get("name"))));

        Map<String, Object> callServiceContainer = firstContainer(manifest(manifests, "Deployment", "call-service"));
        assertEnvFromSecret(callServiceContainer, "mochat-livekit");
    }

    private List<Map<String, Object>> renderChart(String... extraArgs) throws Exception {
        return renderChartWithValues("values-local.yaml", extraArgs);
    }

    private List<Map<String, Object>> renderChartWithValues(String valuesFileName, String... extraArgs) throws Exception {
        Path root = repositoryRoot();
        List<String> command = new ArrayList<>(List.of(
            "helm",
            "template",
            "mochat",
            root.resolve("deploy/helm/mochat").toString(),
            "-f",
            root.resolve("deploy/helm/mochat").resolve(valuesFileName).toString()
        ));
        command.addAll(List.of(extraArgs));
        Process process = new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start();
        ProcessOutput processOutput = ProcessOutput.capture(process);

        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            processOutput.awaitReader();
            fail("helm template timed out:\n" + processOutput.text());
        }

        processOutput.awaitReader();
        String output = processOutput.text();
        assertEquals(0, process.exitValue(), () -> "helm template failed:\n" + output);

        List<Map<String, Object>> result = new ArrayList<>();
        Yaml yaml = new Yaml();
        for (Object document : yaml.loadAll(output)) {
            if (document instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) raw;
                result.add(typed);
            }
        }
        assertTrue(!result.isEmpty(), () -> "helm template rendered no yaml documents:\n" + output);
        return result;
    }

    private void assertHasManifest(List<Map<String, Object>> manifests, String kind, String name) {
        manifest(manifests, kind, name);
    }

    private void assertNoManifest(List<Map<String, Object>> manifests, String kind, String name) {
        assertTrue(
            manifests.stream()
                .filter(entry -> kind.equals(entry.get("kind")))
                .noneMatch(entry -> name.equals(nestedMap(entry, "metadata").get("name"))),
            () -> "Unexpected " + kind + "/" + name
        );
    }

    private Map<String, Object> manifest(List<Map<String, Object>> manifests, String kind, String name) {
        return manifests.stream()
            .filter(entry -> kind.equals(entry.get("kind")))
            .filter(entry -> name.equals(nestedMap(entry, "metadata").get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + kind + "/" + name));
    }

    private Map<String, Object> firstContainer(Map<String, Object> workload) {
        List<Map<String, Object>> containers = nestedList(nestedMap(workload, "spec", "template", "spec"), "containers");
        assertEquals(1, containers.size(), () -> "Expected exactly one container in " + workloadName(workload));
        return containers.get(0);
    }

    private String workloadName(Map<String, Object> workload) {
        Map<String, Object> metadata = nestedMap(workload, "metadata");
        return workload.get("kind") + "/" + metadata.get("name");
    }

    private void assertWorkloadLabels(Map<String, Object> workload, String serviceName) {
        Map<String, Object> labels = nestedMap(workload, "spec", "template", "metadata", "labels");
        assertEquals(serviceName, labels.get("app.kubernetes.io/name"));
        assertEquals("mochat", labels.get("app.kubernetes.io/part-of"));
        assertEquals("mochat", labels.get("app.kubernetes.io/instance"));
        assertEquals("mochat-local", labels.get("mochat.lystran.io/project-id"));
        assertEquals("local", labels.get("mochat.lystran.io/environment"));
    }

    private void assertPrometheusAnnotation(Map<String, Object> workload, String port) {
        Map<String, Object> annotations = nestedMap(workload, "spec", "template", "metadata", "annotations");
        assertEquals("true", annotations.get("prometheus.io/scrape"));
        assertEquals("/prometheus", annotations.get("prometheus.io/path"));
        assertEquals(port, annotations.get("prometheus.io/port"));
    }

    private void assertEnvFromSecret(Map<String, Object> container, String name) {
        List<Map<String, Object>> envFrom = nestedList(container, "envFrom");
        assertTrue(
            envFrom.stream().anyMatch(entry ->
                entry.containsKey("secretRef") && Objects.equals(name, nestedMap(entry, "secretRef").get("name"))
            ),
            () -> "Missing secretRef " + name
        );
    }

    private void assertHasContainerPort(Map<String, Object> container, String name, int port) {
        List<Map<String, Object>> ports = nestedList(container, "ports");
        assertTrue(
            ports.stream().anyMatch(entry -> Objects.equals(name, entry.get("name")) && Objects.equals(port, entry.get("containerPort"))),
            () -> "Missing container port " + name + "=" + port
        );
    }

    private void assertHasServicePort(Map<String, Object> service, String name, int port) {
        List<Map<String, Object>> ports = nestedList(nestedMap(service, "spec"), "ports");
        assertTrue(
            ports.stream().anyMatch(entry -> Objects.equals(name, entry.get("name")) && Objects.equals(port, entry.get("port"))),
            () -> "Missing service port " + name + "=" + port
        );
    }

    private void assertHasServiceNodePort(Map<String, Object> service, String name, int nodePort) {
        List<Map<String, Object>> ports = nestedList(nestedMap(service, "spec"), "ports");
        assertTrue(
            ports.stream().anyMatch(entry -> Objects.equals(name, entry.get("name")) && Objects.equals(nodePort, entry.get("nodePort"))),
            () -> "Missing service nodePort " + name + "=" + nodePort
        );
    }

    private void assertServiceType(Map<String, Object> service, String type) {
        assertEquals(type, nestedMap(service, "spec").get("type"));
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Could not locate repository root");
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> source, String... path) {
        Object current = source;
        for (String segment : path) {
            assertTrue(current instanceof Map<?, ?>, () -> "Expected map at " + String.join(".", path));
            current = ((Map<String, Object>) current).get(segment);
            assertNotNull(current, () -> "Missing path " + String.join(".", path));
        }
        return (Map<String, Object>) current;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nestedList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof List<?>, () -> "Expected list at " + key);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : (List<Object>) value) {
            assertTrue(entry instanceof Map<?, ?>, () -> "Expected map in " + key);
            result.add((Map<String, Object>) entry);
        }
        return result;
    }

    private static final class ProcessOutput {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final Thread reader;

        private ProcessOutput(Process process) {
            reader = new Thread(() -> {
                try {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = process.getInputStream().read(buffer)) != -1) {
                        synchronized (output) {
                            output.write(buffer, 0, bytesRead);
                        }
                    }
                } catch (IOException exception) {
                    synchronized (output) {
                        output.writeBytes(("\n[failed to read helm output: " + exception.getMessage() + "]")
                            .getBytes(StandardCharsets.UTF_8));
                    }
                }
            }, "helm-template-output-reader");
            reader.setDaemon(true);
        }

        static ProcessOutput capture(Process process) {
            ProcessOutput processOutput = new ProcessOutput(process);
            processOutput.reader.start();
            return processOutput;
        }

        void awaitReader() throws InterruptedException {
            reader.join(TimeUnit.SECONDS.toMillis(5));
        }

        String text() {
            synchronized (output) {
                return output.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
