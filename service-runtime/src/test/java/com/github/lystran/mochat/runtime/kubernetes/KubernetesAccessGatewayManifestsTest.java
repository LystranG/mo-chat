package com.github.lystran.mochat.runtime.kubernetes;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesAccessGatewayManifestsTest {
    private static final Path MANIFEST = manifestsRoot().resolve("access-gateway.yaml");

    @Test
    void accessGatewayStatefulSetUsesHeadlessServiceAndPodMetadata() throws IOException {
        Map<String, Object> statefulSet = resource(loadResources(), "StatefulSet", "access-gateway");
        assertEquals("mochat", nestedMap(statefulSet, "metadata").get("namespace"));

        Map<String, Object> spec = nestedMap(statefulSet, "spec");
        assertEquals("access-gateway-headless", spec.get("serviceName"));

        Map<String, Object> container = firstContainer(statefulSet);
        assertEquals("mochat/access-gateway:dev", container.get("image"));
        assertEquals("IfNotPresent", container.get("imagePullPolicy"));

        List<Map<String, Object>> envFrom = nestedList(container, "envFrom");
        assertTrue(envFrom.contains(Map.of("configMapRef", Map.of("name", "mochat-runtime-config"))));

        List<Map<String, Object>> env = nestedList(container, "env");
        assertTrue(env.contains(env("MOCHAT_ACCESS_GATEWAY_TCP_PORT", "9000")));
        assertTrue(env.contains(env("MOCHAT_ACCESS_GATEWAY_GRPC_PORT", "19093")));
        assertTrue(env.contains(fieldRefEnv("MOCHAT_RUNTIME_POD_NAME", "metadata.name")));
        assertTrue(env.contains(fieldRefEnv("MOCHAT_RUNTIME_POD_NAMESPACE", "metadata.namespace")));
    }

    @Test
    void headlessServiceExposesGrpcPodDnsOnly() throws IOException {
        Map<String, Object> service = resource(loadResources(), "Service", "access-gateway-headless");
        Map<String, Object> spec = nestedMap(service, "spec");
        assertEquals("None", spec.get("clusterIP"));
        assertEquals(
            List.of(port("grpc", 19093, 19093)),
            nestedList(spec, "ports")
        );
    }

    @Test
    void externalTcpServiceSeparatesClientIngressFromInternalGrpc() throws IOException {
        Map<String, Object> service = resource(loadResources(), "Service", "access-gateway-tcp");
        Map<String, Object> spec = nestedMap(service, "spec");
        assertEquals("NodePort", spec.get("type"));

        List<Map<String, Object>> ports = nestedList(spec, "ports");
        assertEquals(1, ports.size());
        assertEquals("tcp", ports.get(0).get("name"));
        assertEquals(9000, ports.get(0).get("port"));
        assertEquals(9000, ports.get(0).get("targetPort"));
    }

    @Test
    void statefulSetDefinesReadinessPreStopAndTerminationGraceForDrain() throws IOException {
        Map<String, Object> statefulSet = resource(loadResources(), "StatefulSet", "access-gateway");
        Map<String, Object> podSpec = nestedMap(nestedMap(nestedMap(statefulSet, "spec"), "template"), "spec");
        assertEquals(45, podSpec.get("terminationGracePeriodSeconds"));

        Map<String, Object> container = firstContainer(statefulSet);
        List<Map<String, Object>> ports = nestedList(container, "ports");
        assertTrue(
            ports.stream().anyMatch(entry ->
                Objects.equals("admin", entry.get("name")) && Objects.equals(18080, entry.get("containerPort"))
            )
        );

        List<Map<String, Object>> env = nestedList(container, "env");
        assertTrue(env.contains(env("MOCHAT_ACCESS_GATEWAY_HTTP_PORT", "18080")));
        assertTrue(env.contains(env("MOCHAT_ACCESS_GATEWAY_DRAIN_GRACE_PERIOD", "30s")));
        assertTrue(env.contains(env("MOCHAT_ACCESS_GATEWAY_DRAIN_SHUTDOWN_WAIT_ENABLED", "true")));

        Map<String, Object> readinessProbe = nestedMap(container, "readinessProbe");
        Map<String, Object> readinessHttpGet = nestedMap(readinessProbe, "httpGet");
        assertEquals("/internal/lifecycle/readyz", readinessHttpGet.get("path"));
        assertEquals(18080, readinessHttpGet.get("port"));

        Map<String, Object> lifecycle = nestedMap(container, "lifecycle");
        Map<String, Object> preStop = nestedMap(lifecycle, "preStop");
        Map<String, Object> preStopHttpGet = nestedMap(preStop, "httpGet");
        assertEquals("/internal/lifecycle/drain", preStopHttpGet.get("path"));
        assertEquals(18080, preStopHttpGet.get("port"));
    }

    private static List<Map<String, Object>> loadResources() throws IOException {
        assertTrue(Files.exists(MANIFEST), () -> "Missing manifest file: " + MANIFEST);
        List<Map<String, Object>> resources = new ArrayList<>();
        for (Object document : new Yaml().loadAll(Files.newBufferedReader(MANIFEST))) {
            if (document instanceof Map<?, ?> map) {
                resources.add(normalizeMap(map));
            }
        }
        assertTrue(!resources.isEmpty(), () -> "Expected resources in " + MANIFEST);
        return resources;
    }

    private static Map<String, Object> resource(List<Map<String, Object>> resources, String kind, String name) {
        return resources.stream()
            .filter(resource -> kind.equals(resource.get("kind")))
            .filter(resource -> name.equals(nestedMap(resource, "metadata").get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing resource " + kind + "/" + name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstContainer(Map<String, Object> statefulSet) {
        Map<String, Object> spec = nestedMap(statefulSet, "spec");
        Map<String, Object> template = nestedMap(spec, "template");
        Map<String, Object> podSpec = nestedMap(template, "spec");
        List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
        assertNotNull(containers, "expected containers");
        assertEquals(1, containers.size());
        return containers.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof Map, () -> "Expected map for key '" + key + "' but got " + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nestedList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof List, () -> "Expected list for key '" + key + "' but got " + value);
        return (List<Map<String, Object>>) value;
    }

    private static Path manifestsRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Could not locate repository root");
        return current.resolve("deploy").resolve("kubernetes").resolve("base");
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalizeValue(item));
            }
            return normalized;
        }
        return value;
    }

    private static Map<String, Object> env(String name, String value) {
        return Map.of("name", name, "value", value);
    }

    private static Map<String, Object> fieldRefEnv(String name, String fieldPath) {
        return Map.of(
            "name", name,
            "valueFrom", Map.of(
                "fieldRef", Map.of("fieldPath", fieldPath)
            )
        );
    }

    private static Map<String, Object> port(String name, int port, int targetPort) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name);
        spec.put("port", port);
        spec.put("targetPort", targetPort);
        return spec;
    }
}
