package com.github.lystran.mochat.runtime.kubernetes;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesWorkloadManifestContractTest {
    @Test
    void workloadsReferenceDedicatedRuntimeAndExternalDependencyConfig() throws IOException {
        assertWorkloadHasEnvFrom("api-service.yaml", "Deployment", "api-service", "mochat-runtime-config");
        assertWorkloadHasEnvFrom("api-service.yaml", "Deployment", "api-service", "mochat-external-dependencies");
        assertWorkloadHasEnvFrom("message-service.yaml", "Deployment", "message-service", "mochat-runtime-config");
        assertWorkloadHasEnvFrom("message-service.yaml", "Deployment", "message-service", "mochat-external-dependencies");
        assertWorkloadHasEnvFrom("persistence-service.yaml", "Deployment", "persistence-service", "mochat-runtime-config");
        assertWorkloadHasEnvFrom("persistence-service.yaml", "Deployment", "persistence-service", "mochat-external-dependencies");
        assertWorkloadHasEnvFrom("access-gateway.yaml", "StatefulSet", "access-gateway", "mochat-runtime-config");
        assertWorkloadHasEnvFrom("access-gateway.yaml", "StatefulSet", "access-gateway", "mochat-external-dependencies");
    }

    @Test
    void sensitiveConfigurationUsesDedicatedSecrets() throws IOException {
        Map<String, Object> credentialsSecret = loadManifest("runtime-secrets.yaml", "Secret", "mochat-external-dependency-secrets");
        assertEquals("Opaque", credentialsSecret.get("type"));
        Map<String, Object> credentialsData = nestedMap(credentialsSecret, "stringData");
        assertEquals("", credentialsData.get("MOCHAT_POSTGRES_USERNAME"));
        assertEquals("", credentialsData.get("MOCHAT_POSTGRES_PASSWORD"));

        Map<String, Object> tlsSecret = loadManifest("runtime-secrets.yaml", "Secret", "access-gateway-tls");
        assertEquals("kubernetes.io/tls", tlsSecret.get("type"));
        Map<String, Object> tlsData = nestedMap(tlsSecret, "stringData");
        assertEquals("", tlsData.get("tls.crt"));
        assertEquals("", tlsData.get("tls.key"));
    }

    @Test
    void workloadsReferenceOnlyTheSecretsTheyActuallyNeed() throws IOException {
        assertWorkloadDoesNotHaveSecretRef("api-service.yaml", "Deployment", "api-service");
        assertWorkloadDoesNotHaveSecretRef("message-service.yaml", "Deployment", "message-service");
        assertWorkloadHasSecretRef(
            "persistence-service.yaml",
            "Deployment",
            "persistence-service",
            "mochat-external-dependency-secrets"
        );

        Map<String, Object> gateway = loadManifest("access-gateway.yaml", "StatefulSet", "access-gateway");
        List<Map<String, Object>> containers = nestedList(nestedMap(gateway, "spec", "template", "spec"), "containers");
        assertEquals(1, containers.size());
        Map<String, Object> container = containers.get(0);
        assertEquals("/var/run/mochat/tls", volumeMountPath(container, "access-gateway-tls"));
        assertEquals("/var/run/mochat/tls/tls.crt", envValue(container, "MOCHAT_ACCESS_GATEWAY_TLS_CERTIFICATE_PATH"));
        assertEquals("/var/run/mochat/tls/tls.key", envValue(container, "MOCHAT_ACCESS_GATEWAY_TLS_PRIVATE_KEY_PATH"));
        assertEquals("false", envValue(container, "MOCHAT_ACCESS_GATEWAY_TLS_SELF_SIGNED"));
        assertSecretVolume(gateway, "access-gateway-tls", "access-gateway-tls");
    }

    @Test
    void apiAndMessageServicesExposeClusterIpContracts() throws IOException {
        Map<String, Object> apiService = loadManifest("api-service.yaml", "Service", "api-service");
        assertEquals("ClusterIP", nestedString(apiService, "spec", "type"));
        assertHasNamedPort(apiService, "http", 8080);
        assertHasNamedPort(apiService, "grpc", 19091);

        Map<String, Object> messageService = loadManifest("message-service.yaml", "Service", "message-service");
        assertEquals("ClusterIP", nestedString(messageService, "spec", "type"));
        assertHasNamedPort(messageService, "grpc", 19092);
    }

    @Test
    void sharedRuntimeConfigSeparatesInternalDiscoveryFromExternalDependencyEndpoints() throws IOException {
        Map<String, Object> runtimeConfig = loadManifest("shared-runtime.yaml", "ConfigMap", "mochat-runtime-config");
        Map<String, Object> runtimeData = nestedMap(runtimeConfig, "data");
        assertEquals("api-service:19091", runtimeData.get("MOCHAT_API_SERVICE_GRPC_ADDRESS"));
        assertEquals("message-service:19092", runtimeData.get("MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS"));
        assertEquals("access-gateway-headless", runtimeData.get("MOCHAT_GATEWAY_HEADLESS_SERVICE"));
        assertFalse(runtimeData.containsKey("MOCHAT_REDIS_URI"));
        assertFalse(runtimeData.containsKey("MOCHAT_POSTGRES_URL"));
        assertFalse(runtimeData.containsKey("MOCHAT_ROCKETMQ_NAME_SERVER"));

        Map<String, Object> externalDependencies = loadManifest("shared-runtime.yaml", "ConfigMap", "mochat-external-dependencies");
        Map<String, Object> externalData = nestedMap(externalDependencies, "data");
        assertFalse(String.valueOf(externalData.get("MOCHAT_REDIS_URI")).contains("127.0.0.1"));
        assertFalse(String.valueOf(externalData.get("MOCHAT_POSTGRES_URL")).contains("127.0.0.1"));
        assertFalse(String.valueOf(externalData.get("MOCHAT_ROCKETMQ_NAME_SERVER")).contains("127.0.0.1"));
        assertEquals("mochat.messages", externalData.get("MOCHAT_ROCKETMQ_TOPIC"));
    }

    @Test
    void accessGatewayRunsAsStatefulSetWithStablePodIdentityInputs() throws IOException {
        Map<String, Object> statefulSet = loadManifest("access-gateway.yaml", "StatefulSet", "access-gateway");
        assertEquals("access-gateway-headless", nestedString(statefulSet, "spec", "serviceName"));

        List<Map<String, Object>> containers = nestedList(
            nestedMap(statefulSet, "spec", "template", "spec"),
            "containers"
        );
        assertEquals(1, containers.size());
        List<Map<String, Object>> env = nestedList(containers.get(0), "env");
        assertTrue(
            env.stream().anyMatch(entry ->
                Objects.equals(entry.get("name"), "MOCHAT_RUNTIME_POD_NAME")
                    && nestedString(nestedMap(entry, "valueFrom", "fieldRef"), "fieldPath").equals("metadata.name")
            )
        );
        assertTrue(
            env.stream().anyMatch(entry ->
                Objects.equals(entry.get("name"), "MOCHAT_RUNTIME_POD_NAMESPACE")
                    && nestedString(nestedMap(entry, "valueFrom", "fieldRef"), "fieldPath").equals("metadata.namespace")
            )
        );
    }

    @Test
    void accessGatewaySeparatesGrpcDiscoveryFromExternalTcpIngress() throws IOException {
        Map<String, Object> headlessService = loadManifest("access-gateway.yaml", "Service", "access-gateway-headless");
        assertEquals("None", nestedString(headlessService, "spec", "clusterIP"));
        assertHasNamedPort(headlessService, "grpc", 19093);

        Map<String, Object> tcpService = loadManifest("access-gateway.yaml", "Service", "access-gateway-tcp");
        assertEquals("NodePort", nestedString(tcpService, "spec", "type"));
        assertHasNamedPort(tcpService, "tcp", 9000);
        List<Map<String, Object>> ports = nestedList(nestedMap(tcpService, "spec"), "ports");
        assertFalse(ports.stream().anyMatch(entry -> Objects.equals(entry.get("name"), "grpc")));
    }

    @Test
    void accessGatewayDrainLifecycleUsesReadinessPreStopAndTerminationGracePeriod() throws IOException {
        Map<String, Object> statefulSet = loadManifest("access-gateway.yaml", "StatefulSet", "access-gateway");
        Map<String, Object> podSpec = nestedMap(statefulSet, "spec", "template", "spec");
        assertEquals("45", String.valueOf(podSpec.get("terminationGracePeriodSeconds")));

        List<Map<String, Object>> containers = nestedList(podSpec, "containers");
        assertEquals(1, containers.size());
        Map<String, Object> container = containers.get(0);
        assertHasNamedContainerPort(container, "admin", 18080);
        assertEquals("18080", envValue(container, "MOCHAT_ACCESS_GATEWAY_HTTP_PORT"));
        assertEquals("30s", envValue(container, "MOCHAT_ACCESS_GATEWAY_DRAIN_GRACE_PERIOD"));
        assertEquals("true", envValue(container, "MOCHAT_ACCESS_GATEWAY_DRAIN_SHUTDOWN_WAIT_ENABLED"));

        Map<String, Object> readinessHttpGet = nestedMap(container, "readinessProbe", "httpGet");
        assertEquals("/internal/lifecycle/readyz", readinessHttpGet.get("path"));
        assertEquals("18080", String.valueOf(readinessHttpGet.get("port")));

        Map<String, Object> livenessHttpGet = nestedMap(container, "livenessProbe", "httpGet");
        assertEquals("/internal/lifecycle/livez", livenessHttpGet.get("path"));
        assertEquals("18080", String.valueOf(livenessHttpGet.get("port")));

        Map<String, Object> preStopHttpGet = nestedMap(container, "lifecycle", "preStop", "httpGet");
        assertEquals("/internal/lifecycle/drain", preStopHttpGet.get("path"));
        assertEquals("18080", String.valueOf(preStopHttpGet.get("port")));
    }

    @Test
    void kustomizeBasePackagesAllRepositoryManagedResources() throws IOException {
        Map<String, Object> baseKustomization = loadYamlDocument(manifestsRoot().resolve("kustomization.yaml"));
        assertEquals("Kustomization", baseKustomization.get("kind"));
        List<String> resources = stringList(baseKustomization, "resources");
        assertTrue(resources.contains("shared-runtime.yaml"));
        assertTrue(resources.contains("runtime-secrets.yaml"));
        assertTrue(resources.contains("api-service.yaml"));
        assertTrue(resources.contains("message-service.yaml"));
        assertTrue(resources.contains("persistence-service.yaml"));
        assertTrue(resources.contains("access-gateway.yaml"));
    }

    @Test
    void kindOverlayIsTheStandardRepositoryDeliveryEntryPoint() throws IOException {
        Path overlayPath = repositoryRoot().resolve("deploy").resolve("kubernetes").resolve("overlays").resolve("kind").resolve("kustomization.yaml");
        Map<String, Object> overlayKustomization = loadYamlDocument(overlayPath);
        assertEquals("Kustomization", overlayKustomization.get("kind"));
        List<String> resources = stringList(overlayKustomization, "resources");
        assertEquals(List.of("../../base"), resources);
    }

    private void assertWorkloadHasEnvFrom(String fileName, String kind, String workloadName, String configMapName) throws IOException {
        List<Map<String, Object>> envFrom = envFromEntries(loadManifest(fileName, kind, workloadName));
        assertTrue(
            envFrom.stream().anyMatch(entry ->
                entry.containsKey("configMapRef")
                    && Objects.equals(nestedMap(entry, "configMapRef").get("name"), configMapName)
            )
        );
    }

    private void assertWorkloadHasSecretRef(String fileName, String kind, String workloadName, String secretName) throws IOException {
        List<Map<String, Object>> envFrom = envFromEntries(loadManifest(fileName, kind, workloadName));
        assertTrue(
            envFrom.stream().anyMatch(entry ->
                entry.containsKey("secretRef")
                    && Objects.equals(nestedMap(entry, "secretRef").get("name"), secretName)
            )
        );
    }

    private void assertWorkloadDoesNotHaveSecretRef(String fileName, String kind, String workloadName) throws IOException {
        List<Map<String, Object>> envFrom = envFromEntries(loadManifest(fileName, kind, workloadName));
        assertFalse(envFrom.stream().anyMatch(entry -> entry.containsKey("secretRef")));
    }

    private List<Map<String, Object>> envFromEntries(Map<String, Object> workload) {
        List<Map<String, Object>> containers = nestedList(
            nestedMap(workload, "spec", "template", "spec"),
            "containers"
        );
        assertEquals(1, containers.size());
        return nestedList(containers.get(0), "envFrom");
    }

    private String volumeMountPath(Map<String, Object> container, String mountName) {
        List<Map<String, Object>> volumeMounts = nestedList(container, "volumeMounts");
        return volumeMounts.stream()
            .filter(entry -> Objects.equals(mountName, entry.get("name")))
            .map(entry -> String.valueOf(entry.get("mountPath")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing volumeMount " + mountName));
    }

    private void assertSecretVolume(Map<String, Object> workload, String volumeName, String secretName) {
        Map<String, Object> podSpec = nestedMap(workload, "spec", "template", "spec");
        List<Map<String, Object>> volumes = nestedList(podSpec, "volumes");
        assertTrue(
            volumes.stream().anyMatch(entry ->
                Objects.equals(volumeName, entry.get("name"))
                    && Objects.equals(secretName, nestedMap(entry, "secret").get("secretName"))
            ),
            () -> "Expected secret volume %s -> %s".formatted(volumeName, secretName)
        );
    }

    private void assertHasNamedPort(Map<String, Object> service, String name, int port) {
        List<Map<String, Object>> ports = nestedList(nestedMap(service, "spec"), "ports");
        assertTrue(
            ports.stream().anyMatch(entry -> Objects.equals(name, entry.get("name")) && Objects.equals(port, entry.get("port"))),
            () -> "Expected named port %s=%s in service".formatted(name, port)
        );
    }

    private void assertHasNamedContainerPort(Map<String, Object> container, String name, int port) {
        List<Map<String, Object>> ports = nestedList(container, "ports");
        assertTrue(
            ports.stream().anyMatch(entry ->
                Objects.equals(name, entry.get("name")) && Objects.equals(port, entry.get("containerPort"))
            ),
            () -> "Expected named container port %s=%s".formatted(name, port)
        );
    }

    private String envValue(Map<String, Object> container, String name) {
        List<Map<String, Object>> env = nestedList(container, "env");
        return env.stream()
            .filter(entry -> Objects.equals(name, entry.get("name")))
            .map(entry -> String.valueOf(entry.get("value")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing env " + name));
    }

    private Map<String, Object> loadManifest(String fileName, String kind, String name) throws IOException {
        Path manifestPath = manifestsRoot().resolve(fileName);
        assertTrue(Files.exists(manifestPath), () -> "Missing manifest: " + manifestPath);

        Yaml yaml = new Yaml();
        for (Object document : yaml.loadAll(Files.newBufferedReader(manifestPath))) {
            if (!(document instanceof Map<?, ?> rawDocument)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedDocument = (Map<String, Object>) rawDocument;
            if (kind.equals(typedDocument.get("kind")) && name.equals(nestedMap(typedDocument, "metadata").get("name"))) {
                return typedDocument;
            }
        }
        throw new AssertionError("Missing %s/%s in %s".formatted(kind, name, manifestPath));
    }

    private Path manifestsRoot() {
        return repositoryRoot().resolve("deploy").resolve("kubernetes").resolve("base");
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
    private Map<String, Object> loadYamlDocument(Path path) throws IOException {
        assertTrue(Files.exists(path), () -> "Missing yaml document: " + path);
        Object document = new Yaml().load(Files.newBufferedReader(path));
        assertTrue(document instanceof Map<?, ?>, () -> "Expected yaml map in " + path);
        return (Map<String, Object>) document;
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
        assertNotNull(value, () -> "Missing list " + key);
        assertTrue(value instanceof List<?>, () -> "Expected list at " + key);
        List<Map<String, Object>> typed = new ArrayList<>();
        for (Object entry : (List<Object>) value) {
            assertTrue(entry instanceof Map<?, ?>, () -> "Expected map entries in " + key);
            typed.add((Map<String, Object>) entry);
        }
        return typed;
    }

    private String nestedString(Map<String, Object> source, String... path) {
        Object current = source;
        for (String segment : path) {
            assertTrue(current instanceof Map<?, ?>, () -> "Expected map at " + String.join(".", path));
            current = ((Map<String, Object>) current).get(segment);
            assertNotNull(current, () -> "Missing path " + String.join(".", path));
        }
        return String.valueOf(current);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertNotNull(value, () -> "Missing list " + key);
        assertTrue(value instanceof List<?>, () -> "Expected list at " + key);
        List<String> result = new ArrayList<>();
        for (Object entry : (List<Object>) value) {
            result.add(String.valueOf(entry));
        }
        return result;
    }
}
