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

class KubernetesKindOverlayContractTest {
    @Test
    void kindOverlayReplacesExternalDependencyConfigAndSecretInputs() throws IOException {
        Map<String, Object> kustomization = loadYamlDocument(overlayRoot().resolve("kustomization.yaml"));
        assertEquals("Kustomization", kustomization.get("kind"));
        assertEquals(List.of("../../base"), stringList(kustomization, "resources"));

        Map<String, Object> generatorOptions = nestedMap(kustomization, "generatorOptions");
        assertEquals(Boolean.TRUE, generatorOptions.get("disableNameSuffixHash"));

        List<Map<String, Object>> images = mapList(kustomization, "images");
        assertTrue(images.stream().anyMatch(entry ->
            Objects.equals("mochat/access-gateway", entry.get("name"))
                && Objects.equals("localhost/mochat/access-gateway", entry.get("newName"))
                && Objects.equals("dev", String.valueOf(entry.get("newTag")))
        ));
        assertTrue(images.stream().anyMatch(entry ->
            Objects.equals("mochat/api-service", entry.get("name"))
                && Objects.equals("localhost/mochat/api-service", entry.get("newName"))
                && Objects.equals("dev", String.valueOf(entry.get("newTag")))
        ));
        assertTrue(images.stream().anyMatch(entry ->
            Objects.equals("mochat/message-service", entry.get("name"))
                && Objects.equals("localhost/mochat/message-service", entry.get("newName"))
                && Objects.equals("dev", String.valueOf(entry.get("newTag")))
        ));
        assertTrue(images.stream().anyMatch(entry ->
            Objects.equals("mochat/persistence-service", entry.get("name"))
                && Objects.equals("localhost/mochat/persistence-service", entry.get("newName"))
                && Objects.equals("dev", String.valueOf(entry.get("newTag")))
        ));

        List<Map<String, Object>> configMapGenerators = mapList(kustomization, "configMapGenerator");
        assertTrue(
            configMapGenerators.stream().anyMatch(entry ->
                Objects.equals("mochat-external-dependencies", entry.get("name"))
                    && Objects.equals("replace", entry.get("behavior"))
                    && stringList(entry, "envs").contains(".local/external-dependencies.env")
            ),
            "kind overlay must replace external dependency endpoints with local kind inputs"
        );

        List<Map<String, Object>> secretGenerators = mapList(kustomization, "secretGenerator");
        assertTrue(
            secretGenerators.stream().anyMatch(entry ->
                Objects.equals("mochat-external-dependency-secrets", entry.get("name"))
                    && Objects.equals("replace", entry.get("behavior"))
                    && stringList(entry, "envs").contains(".local/external-dependency-secrets.env")
            ),
            "kind overlay must replace external dependency secrets from local env inputs"
        );
        assertTrue(
            secretGenerators.stream().anyMatch(entry ->
                Objects.equals("access-gateway-tls", entry.get("name"))
                    && Objects.equals("replace", entry.get("behavior"))
                    && Objects.equals("kubernetes.io/tls", entry.get("type"))
                    && stringList(entry, "files").contains("tls.crt=.local/access-gateway-tls/tls.crt")
                    && stringList(entry, "files").contains("tls.key=.local/access-gateway-tls/tls.key")
            ),
            "kind overlay must replace gateway TLS secret from local files"
        );
    }

    @Test
    void kindVerificationAssetsExistAndDocumentRequiredChecks() throws IOException {
        Path overlayRoot = overlayRoot();
        Path ignoreFile = overlayRoot.resolve(".gitignore");
        Path prepareScript = overlayRoot.resolve("prepare-local-inputs.sh");
        Path minimalVerifyScript = overlayRoot.resolve("verify-minimal-topology.sh");
        Path routingVerifyScript = overlayRoot.resolve("verify-routing-and-drain.sh");

        assertTrue(Files.exists(ignoreFile), "missing overlay-local ignore file");
        assertTrue(Files.readString(ignoreFile).contains(".local/"), "overlay ignore file must exclude generated local inputs");

        assertTrue(Files.exists(prepareScript), "missing local input preparation script");
        String prepareText = Files.readString(prepareScript);
        assertTrue(prepareText.contains("external-dependencies.env"));
        assertTrue(prepareText.contains("external-dependency-secrets.env"));
        assertTrue(prepareText.contains("podman inspect"));
        assertTrue(prepareText.contains("MOCHAT_KIND_POSTGRES_HOST"));
        assertTrue(prepareText.contains("MOCHAT_KIND_REDIS_HOST"));
        assertTrue(prepareText.contains("MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST"));
        assertTrue(prepareText.contains("MOCHAT_KIND_ROCKETMQ_BROKER_HOST"));
        assertTrue(prepareText.contains("host.containers.internal"));
        assertTrue(prepareText.contains("openssl req -x509"));
        assertTrue(prepareText.contains("access-gateway-tls"));

        assertTrue(Files.exists(minimalVerifyScript), "missing minimal kind verification script");
        String minimalVerifyText = Files.readString(minimalVerifyScript);
        assertTrue(minimalVerifyText.contains("podman save"));
        assertTrue(minimalVerifyText.contains("kind load image-archive"));
        assertTrue(minimalVerifyText.contains("localhost/mochat/access-gateway:dev"));
        assertTrue(minimalVerifyText.contains("localhost/mochat/api-service:dev"));
        assertTrue(minimalVerifyText.contains("localhost/mochat/message-service:dev"));
        assertTrue(minimalVerifyText.contains("localhost/mochat/persistence-service:dev"));
        assertTrue(minimalVerifyText.contains("kubectl apply -k"));
        assertTrue(minimalVerifyText.contains("app.kubernetes.io/name=access-gateway"));
        assertTrue(minimalVerifyText.contains("api-service.mochat.svc.cluster.local"));
        assertTrue(minimalVerifyText.contains("access-gateway-0.access-gateway-headless.mochat.svc.cluster.local"));
        assertTrue(minimalVerifyText.contains("${MOCHAT_KIND_POSTGRES_HOST}:5432"));
        assertTrue(minimalVerifyText.contains("${MOCHAT_KIND_REDIS_HOST}:6379"));
        assertTrue(minimalVerifyText.contains("${MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST}:9876"));
        assertTrue(minimalVerifyText.contains("${MOCHAT_KIND_ROCKETMQ_BROKER_HOST}:10909"));
        assertTrue(minimalVerifyText.contains("PING"));

        assertTrue(Files.exists(routingVerifyScript), "missing routing/drain verification script");
        String routingVerifyText = Files.readString(routingVerifyScript);
        assertTrue(routingVerifyText.contains("kubectl scale statefulset/access-gateway"));
        assertTrue(routingVerifyText.contains("kubectl rollout restart statefulset/access-gateway"));
        assertTrue(
            routingVerifyText.contains(
                "com.github.lystran.mochat.messageservice.MessageServiceCrossGatewayRoutingIntegrationTest"
            )
        );
        assertTrue(
            routingVerifyText.contains(
                "com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest"
            )
        );
        assertTrue(
            routingVerifyText.contains(
                "com.github.lystran.mochat.accessgateway.AccessGatewayLifecycleEndpointTest"
            )
        );
    }

    @Test
    void routingVerificationRecoversDrainedGatewayBeforeRollingRestart() throws IOException {
        String routingVerifyText = Files.readString(overlayRoot().resolve("verify-routing-and-drain.sh"));

        int drainIndex = routingVerifyText.indexOf("/internal/lifecycle/drain");
        int deleteIndex = routingVerifyText.indexOf("kubectl delete pod access-gateway-0 -n \"${namespace}\"");
        int recoveryStatusIndex = routingVerifyText.indexOf(
            "kubectl rollout status statefulset/access-gateway -n \"${namespace}\"",
            deleteIndex
        );
        int restartIndex = routingVerifyText.indexOf(
            "kubectl rollout restart statefulset/access-gateway -n \"${namespace}\""
        );

        assertTrue(drainIndex >= 0, "routing verification must drain a gateway before checking recovery");
        assertTrue(deleteIndex > drainIndex, "drained gateway must be recreated before rolling restart begins");
        assertTrue(recoveryStatusIndex > deleteIndex, "script must wait for the recreated gateway to become ready");
        assertTrue(restartIndex > recoveryStatusIndex, "rolling restart must run only after drain recovery completes");
    }

    @Test
    void kindVerificationScriptsResolveRepositoryRootFromOverlayDirectory() throws IOException {
        String minimalVerifyText = Files.readString(overlayRoot().resolve("verify-minimal-topology.sh"));
        String routingVerifyText = Files.readString(overlayRoot().resolve("verify-routing-and-drain.sh"));

        assertTrue(
            minimalVerifyText.contains("repo_root=\"$(cd \"${overlay_dir}/../../../..\" && pwd)\""),
            "minimal topology verification must resolve repository root above deploy/kubernetes/overlays/kind"
        );
        assertTrue(
            routingVerifyText.contains("repo_root=\"$(cd \"${overlay_dir}/../../../..\" && pwd)\""),
            "routing verification must resolve repository root above deploy/kubernetes/overlays/kind"
        );
    }

    @Test
    void kindMinimalTopologyVerificationUsesExternalNodePortProbeWithoutDowngradingPersistenceRuntime() throws IOException {
        Path overlayRoot = overlayRoot();
        Map<String, Object> kustomization = loadYamlDocument(overlayRoot.resolve("kustomization.yaml"));
        Object patches = kustomization.get("patches");
        if (patches instanceof List<?> patchEntries) {
            assertFalse(
                patchEntries.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .anyMatch(entry -> Objects.equals("persistence-service-kind-patch.yaml", entry.get("path"))),
                "kind overlay must not globally downgrade persistence-service runtime for minimal topology verification"
            );
        }

        Path persistencePatch = overlayRoot.resolve("persistence-service-kind-patch.yaml");
        assertFalse(Files.exists(persistencePatch), "minimal topology verification must not rely on a shared overlay patch");

        String minimalVerifyText = Files.readString(overlayRoot.resolve("verify-minimal-topology.sh"));
        assertFalse(minimalVerifyText.contains("kubectl set env deployment/persistence-service"));
        assertFalse(minimalVerifyText.contains("MOCHAT_KIND_PERSISTENCE_QUEUE_CONSUMER_ENABLED"));
        assertTrue(minimalVerifyText.contains("KIND_NETWORK"));
        assertTrue(minimalVerifyText.contains("podman run --rm"));
        assertTrue(minimalVerifyText.contains("${node_ip}"));
        assertTrue(minimalVerifyText.contains("${node_port}"));
        assertFalse(minimalVerifyText.contains("127.0.0.1/${node_port}"));
        assertTrue(minimalVerifyText.contains("status.addresses[?(@.type=='InternalIP')]"));
    }

    private Path overlayRoot() {
        return repositoryRoot().resolve("deploy").resolve("kubernetes").resolve("overlays").resolve("kind");
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
    private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof Map<?, ?>, () -> "Expected map at " + key);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof List<?>, () -> "Expected list at " + key);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : (List<Object>) value) {
            assertTrue(entry instanceof Map<?, ?>, () -> "Expected map entries in " + key);
            result.add((Map<String, Object>) entry);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof List<?>, () -> "Expected list at " + key);
        List<String> result = new ArrayList<>();
        for (Object entry : (List<Object>) value) {
            result.add(String.valueOf(entry));
        }
        return result;
    }
}
