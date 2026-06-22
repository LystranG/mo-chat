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

class KubernetesKindOverlayAssetsTest {
    @Test
    void kindOverlayReplacesExternalInputsWithLocalRuntimeAssets() throws IOException {
        Map<String, Object> kustomization = loadYamlDocument(overlayRoot().resolve("kustomization.yaml"));
        assertEquals("Kustomization", kustomization.get("kind"));
        assertEquals(List.of("../../base"), stringList(kustomization, "resources"));
        assertEquals("mochat", kustomization.get("namespace"));

        Map<String, Object> generatorOptions = nestedMap(kustomization, "generatorOptions");
        assertEquals(Boolean.TRUE, generatorOptions.get("disableNameSuffixHash"));

        Map<String, Object> externalDependencies = namedGenerator(
            nestedList(kustomization, "configMapGenerator"),
            "mochat-external-dependencies"
        );
        assertEquals("replace", externalDependencies.get("behavior"));
        assertEquals(List.of(".local/external-dependencies.env"), stringList(externalDependencies, "envs"));

        assertGeneratorWithEnv(
            nestedList(kustomization, "secretGenerator"),
            "mochat-external-dependency-secrets",
            List.of(".local/external-dependency-secrets.env")
        );

        Map<String, Object> tlsGenerator = namedGenerator(nestedList(kustomization, "secretGenerator"), "access-gateway-tls");
        assertEquals("replace", tlsGenerator.get("behavior"));
        assertEquals("kubernetes.io/tls", tlsGenerator.get("type"));
        assertEquals(
            List.of(
                "tls.crt=.local/access-gateway-tls/tls.crt",
                "tls.key=.local/access-gateway-tls/tls.key"
            ),
            stringList(tlsGenerator, "files")
        );

        List<Map<String, Object>> images = nestedList(kustomization, "images");
        assertImage(images, "mochat/api-service", "localhost/mochat/api-service", "dev");
        assertImage(images, "mochat/message-service", "localhost/mochat/message-service", "dev");
        assertImage(images, "mochat/persistence-service", "localhost/mochat/persistence-service", "dev");
        assertImage(images, "mochat/access-gateway", "localhost/mochat/access-gateway", "dev");
    }

    @Test
    void kindVerificationScriptsCoverTopologyAndRoutingChecks() throws IOException {
        String gitignore = Files.readString(overlayRoot().resolve(".gitignore"));
        assertTrue(gitignore.contains(".local/"));

        String prepareScript = Files.readString(overlayRoot().resolve("prepare-local-inputs.sh"));
        assertTrue(prepareScript.contains("external-dependencies.env"));
        assertTrue(prepareScript.contains("docker inspect"));
        assertTrue(prepareScript.contains("ddd-demo-postgres-1"));
        assertTrue(prepareScript.contains("ddd-demo-redis"));
        assertTrue(prepareScript.contains("ddd-demo-rocketmq-namesrv"));
        assertTrue(prepareScript.contains("ddd-demo-rocketmq-broker"));
        assertTrue(prepareScript.contains("MOCHAT_KIND_POSTGRES_HOST"));
        assertTrue(prepareScript.contains("MOCHAT_KIND_REDIS_HOST"));
        assertTrue(prepareScript.contains("MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST"));
        assertTrue(prepareScript.contains("MOCHAT_KIND_ROCKETMQ_BROKER_HOST"));
        assertTrue(prepareScript.contains("MOCHAT_POSTGRES_USERNAME=mochat"));
        assertTrue(prepareScript.contains("MOCHAT_POSTGRES_PASSWORD=mochat"));
        assertTrue(prepareScript.contains("MOCHAT_REDIS_URI=redis://"));
        assertTrue(prepareScript.contains("MOCHAT_POSTGRES_URL=jdbc:postgresql://"));
        assertTrue(prepareScript.contains("MOCHAT_ROCKETMQ_NAME_SERVER="));
        assertTrue(prepareScript.contains("openssl req -x509"));

        String topologyScript = Files.readString(overlayRoot().resolve("verify-minimal-topology.sh"));
        assertTrue(topologyScript.contains("docker save"));
        assertTrue(topologyScript.contains("kind load image-archive"));
        assertTrue(topologyScript.contains("kubectl apply -k"));
        assertTrue(topologyScript.contains("kubectl rollout restart deployment/api-service"));
        assertTrue(topologyScript.contains("kubectl rollout restart deployment/message-service"));
        assertTrue(topologyScript.contains("kubectl rollout restart deployment/persistence-service"));
        assertTrue(topologyScript.contains("kubectl rollout restart statefulset/access-gateway"));
        assertTrue(topologyScript.contains("api-service.mochat.svc.cluster.local"));
        assertTrue(topologyScript.contains("access-gateway-0.access-gateway-headless.mochat.svc.cluster.local"));
        assertTrue(topologyScript.contains("external-dependencies.env"));
        assertTrue(topologyScript.contains("MOCHAT_KIND_POSTGRES_HOST"));
        assertTrue(topologyScript.contains("MOCHAT_KIND_REDIS_HOST"));
        assertTrue(topologyScript.contains("MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST"));
        assertTrue(topologyScript.contains("MOCHAT_KIND_ROCKETMQ_BROKER_HOST"));
        assertTrue(topologyScript.contains("MOCHAT_RUNTIME_POD_NAME"));
        assertTrue(topologyScript.contains("metadata.name"));
        assertTrue(topologyScript.contains("busybox:1.36"));
        assertTrue(topologyScript.contains("nc -vz -w 2"));
        assertFalse(topologyScript.contains("host.containers.internal:5432"));
        assertFalse(topologyScript.contains("host.containers.internal:6379"));
        assertFalse(topologyScript.contains("host.containers.internal:9876"));
        assertFalse(topologyScript.contains("pod" + "man"));
        assertFalse(topologyScript.contains("/dev/tcp"));

        String routingScript = Files.readString(overlayRoot().resolve("verify-routing-and-drain.sh"));
        assertTrue(routingScript.contains("kubectl scale statefulset/access-gateway"));
        assertTrue(routingScript.contains("/internal/lifecycle/drain"));
        assertTrue(routingScript.contains("kubectl rollout restart statefulset/access-gateway"));
        assertTrue(routingScript.contains("MessageServiceCrossGatewayRoutingIntegrationTest"));
        assertTrue(routingScript.contains("newerBindOnOtherGatewayLeavesOldOwnerAliveUntilHeartbeatThenSelfKills"));
        assertTrue(routingScript.contains("drainingGatewayRejectsNewBindButAllowsReconnectOnOtherGatewayAfterGrace"));
        assertTrue(routingScript.contains("AccessGatewayLifecycleEndpointTest"));
        assertTrue(routingScript.contains("GatewayIngressLifecycleTest"));
        assertTrue(routingScript.contains("SKIP_MINIMAL_TOPOLOGY"));
        assertTrue(routingScript.contains("repo_root"));
    }

    @Test
    void runbookDocumentsKindVerificationAndStaticAddressFallback() throws IOException {
        String runbook = Files.readString(repositoryRoot().resolve("docs").resolve("runbook.md"));
        assertTrue(runbook.contains("deploy/kubernetes/base"));
        assertTrue(runbook.contains("deploy/kubernetes/overlays/kind"));
        assertTrue(runbook.contains("bash deploy/kubernetes/overlays/kind/verify-minimal-topology.sh"));
        assertTrue(runbook.contains("verify-routing-and-drain.sh"));
        assertTrue(runbook.contains("external-dependencies.env"));
        assertTrue(runbook.contains("docker inspect"));
        assertTrue(runbook.contains("kind load image-archive"));
        assertTrue(runbook.contains("MOCHAT_RUNTIME_POD_NAME"));
        assertTrue(runbook.contains("MOCHAT_RUNTIME_POD_NAMESPACE"));
        assertTrue(runbook.contains("GRADLE_USER_HOME=\"$PWD/.gradle-user-home\""));
        assertTrue(runbook.contains("ConfigMap"));
        assertTrue(runbook.contains("Secret"));
        assertTrue(runbook.contains("回滚到当前静态地址拓扑"));
        assertTrue(runbook.contains("mochat.message-service.route.gateway-targets"));
        assertFalse(runbook.contains("does not yet contain committed `k8s/` manifests or `kind` automation scripts"));
    }

    private void assertGeneratorWithEnv(List<Map<String, Object>> generators, String name, List<String> envFiles) {
        Map<String, Object> generator = namedGenerator(generators, name);
        assertEquals("replace", generator.get("behavior"));
        assertEquals(envFiles, stringList(generator, "envs"));
    }

    private Map<String, Object> namedGenerator(List<Map<String, Object>> generators, String name) {
        return generators.stream()
            .filter(entry -> Objects.equals(name, entry.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing generator " + name));
    }

    private void assertImage(List<Map<String, Object>> images, String name, String newName, String newTag) {
        Map<String, Object> image = images.stream()
            .filter(entry -> Objects.equals(name, entry.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing image override " + name));
        assertEquals(newName, image.get("newName"));
        assertEquals(newTag, image.get("newTag"));
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
    private List<Map<String, Object>> nestedList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof List<?>, () -> "Expected list at " + key);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : (List<Object>) value) {
            assertTrue(entry instanceof Map<?, ?>, () -> "Expected map entry in " + key);
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
