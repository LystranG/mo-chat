package com.github.lystran.mochat.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRunScriptContractTest {
    private static final List<String> EXPECTED_LOCAL_GRADLE_COMMANDS = List.of(
        "MICRONAUT_ENVIRONMENTS=local ./gradlew :api-service-app:run",
        "MICRONAUT_ENVIRONMENTS=local ./gradlew :message-service-app:run",
        "MICRONAUT_ENVIRONMENTS=local ./gradlew :persistence-service-app:run",
        "MICRONAUT_ENVIRONMENTS=local ./gradlew :access-gateway-app:run",
        "MICRONAUT_ENVIRONMENTS=local ./gradlew :call-service-app:run"
    );
    @TempDir
    Path tempDir;

    @Test
    void missingEnvFileFailsBeforeStartingServices() throws Exception {
        ProcessResult result = runScript(tempDir.resolve("missing.env"), "print-commands");

        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("Missing local env file"));
        assertFalse(result.output().contains("./gradlew"));
    }

    @Test
    void serviceStateCommandsValidateEnvBeforeSideEffects() throws Exception {
        for (String command : List.of("stop", "status", "restart")) {
            ProcessResult result = runScript(tempDir.resolve(command + "-missing.env"), command);

            assertEquals(2, result.exitCode(), command + ": " + result.output());
            assertTrue(result.output().contains("Missing local env file"), command + ": " + result.output());
            assertFalse(result.output().contains("is not running"), command + ": " + result.output());
            assertFalse(result.output().contains("stopped"), command + ": " + result.output());
            assertFalse(result.output().contains("Stopped "), command + ": " + result.output());
        }
    }

    @Test
    void blankLivekitValuesFailBeforeStartingServices() throws Exception {
        Path envFile = tempDir.resolve("blank.env");
        Files.writeString(envFile, """
            MOCHAT_LIVEKIT_URL=
            MOCHAT_LIVEKIT_API_KEY=
            MOCHAT_LIVEKIT_API_SECRET=
            """);

        ProcessResult result = runScript(envFile, "print-commands");

        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("MOCHAT_LIVEKIT_URL is required"));
        assertFalse(result.output().contains("./gradlew"));
    }

    @Test
    void printCommandsShowsFiveLocalGradleProcesses() throws Exception {
        Path envFile = validEnvFile();

        ProcessResult result = runScript(envFile, "print-commands");

        assertEquals(0, result.exitCode(), result.output());
        List<String> gradleCommandLines = gradleCommandLines(result.output());
        assertEquals(
            List.of(),
            gradleCommandLines.stream()
                .filter(line -> !EXPECTED_LOCAL_GRADLE_COMMANDS.contains(line))
                .toList(),
            result.output()
        );
        assertEquals(EXPECTED_LOCAL_GRADLE_COMMANDS, gradleCommandLines, result.output());
    }

    @Test
    void stopTreatsInvalidPidFileAsStaleWithoutSignalling() throws Exception {
        Path pidDir = localPidDir();
        for (String invalidPid : List.of("not-a-pid", "-1")) {
            Files.createDirectories(pidDir);
            Path pidFile = pidDir.resolve("api-service-app.pid");
            Files.writeString(pidFile, invalidPid);

            ProcessResult result = runScript(validEnvFile(), "stop");

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("api-service-app pid file was invalid: " + invalidPid), result.output());
            assertFalse(Files.exists(pidFile), result.output());
        }
    }

    @Test
    void stopDoesNotKillLiveProcessWhenCommandDoesNotMatchService() throws Exception {
        Path pidDir = localPidDir();
        Process sleep = new ProcessBuilder("sleep", "30").start();
        try {
            Files.createDirectories(pidDir);
            Path pidFile = pidDir.resolve("api-service-app.pid");
            Files.writeString(pidFile, Long.toString(sleep.pid()));

            ProcessResult result = runScript(validEnvFile(), "stop");

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("api-service-app pid file was stale: " + sleep.pid()), result.output());
            assertTrue(sleep.isAlive(), result.output());
            assertFalse(Files.exists(pidFile), result.output());
        } finally {
            sleep.destroyForcibly();
            sleep.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopDoesNotKillProcessThatOnlyPretendsToBeGradleServiceRunTask() throws Exception {
        Path pidDir = localPidDir();
        Process fakeService = new ProcessBuilder(
            "bash",
            "-c",
            "exec -a 'fake-gradle .gradle :api-service-app:run' sleep 30"
        ).start();
        try {
            Files.createDirectories(pidDir);
            Path pidFile = pidDir.resolve("api-service-app.pid");
            Files.writeString(pidFile, Long.toString(fakeService.pid()));

            ProcessResult result = runScript(validEnvFile(), "stop");

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("api-service-app pid file was stale: " + fakeService.pid()), result.output());
            assertTrue(fakeService.isAlive(), result.output());
            assertFalse(Files.exists(pidFile), result.output());
        } finally {
            fakeService.destroyForcibly();
            fakeService.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void markerProcessWithoutMatchingTokenIsNotKilled() throws Exception {
        Path root = repositoryRoot();
        Path pidDir = localPidDir();
        String service = "api-service-app";
        String marker = "mochat-local:" + service + ":" + root + ":missing-token";
        ProcessBuilder processBuilder = new ProcessBuilder(
            "bash",
            "-c",
            "exec -a \"$MOCHAT_TEST_MARKER :api-service-app:run\" sleep 30"
        );
        processBuilder.environment().put("MOCHAT_TEST_MARKER", marker);
        Process markedProcess = processBuilder.start();
        try {
            Files.createDirectories(pidDir);
            Path pidFile = pidDir.resolve(service + ".pid");
            Files.writeString(pidFile, Long.toString(markedProcess.pid()));

            ProcessResult status = runScript(validEnvFile(), "status");

            assertEquals(0, status.exitCode(), status.output());
            assertTrue(status.output().contains(service + " stale pid=" + markedProcess.pid()), status.output());

            ProcessResult stop = runScript(validEnvFile(), "stop");

            assertEquals(0, stop.exitCode(), stop.output());
            assertTrue(stop.output().contains(service + " pid file was stale: " + markedProcess.pid()), stop.output());
            assertTrue(markedProcess.isAlive(), stop.output());
            assertFalse(Files.exists(pidFile), stop.output());
        } finally {
            markedProcess.destroyForcibly();
            markedProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void markerProcessWithTokenPrefixIsNotKilled() throws Exception {
        Path root = repositoryRoot();
        Path pidDir = localPidDir();
        String service = "api-service-app";
        String metaToken = "prefix-token";
        String commandToken = metaToken + "-extra";
        String marker = "mochat-local:" + service + ":" + root + ":" + commandToken;
        ProcessBuilder processBuilder = new ProcessBuilder(
            "bash",
            "-c",
            "exec -a \"$MOCHAT_TEST_MARKER :api-service-app:run\" sleep 30"
        );
        processBuilder.environment().put("MOCHAT_TEST_MARKER", marker);
        Process markedProcess = processBuilder.start();
        try {
            Files.createDirectories(pidDir);
            Path pidFile = pidDir.resolve(service + ".pid");
            Path metaFile = pidDir.resolve(service + ".meta");
            Files.writeString(pidFile, Long.toString(markedProcess.pid()));
            Files.writeString(metaFile, "token=" + metaToken + "\n");

            ProcessResult status = runScript(validEnvFile(), "status");

            assertEquals(0, status.exitCode(), status.output());
            assertTrue(status.output().contains(service + " stale pid=" + markedProcess.pid()), status.output());

            ProcessResult stop = runScript(validEnvFile(), "stop");

            assertEquals(0, stop.exitCode(), stop.output());
            assertTrue(stop.output().contains(service + " pid file was stale: " + markedProcess.pid()), stop.output());
            assertTrue(markedProcess.isAlive(), stop.output());
            assertFalse(Files.exists(pidFile), stop.output());
            assertFalse(Files.exists(metaFile), stop.output());
        } finally {
            markedProcess.destroyForcibly();
            markedProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void statusAndStopRecognizeProcessWithLocalRunnerMarkerAndToken() throws Exception {
        Path root = repositoryRoot();
        Path pidDir = localPidDir();
        String service = "api-service-app";
        String token = "test-token-123";
        String marker = "mochat-local:" + service + ":" + root + ":" + token;
        ProcessBuilder processBuilder = new ProcessBuilder(
            "bash",
            "-c",
            "exec -a \"$MOCHAT_TEST_MARKER :api-service-app:run\" sleep 30"
        );
        processBuilder.environment().put("MOCHAT_TEST_MARKER", marker);
        Process markedProcess = processBuilder.start();
        try {
            Files.createDirectories(pidDir);
            Path pidFile = pidDir.resolve(service + ".pid");
            Path metaFile = pidDir.resolve(service + ".meta");
            Files.writeString(pidFile, Long.toString(markedProcess.pid()));
            Files.writeString(metaFile, "token=" + token + "\n");

            ProcessResult status = runScript(validEnvFile(), "status");

            assertEquals(0, status.exitCode(), status.output());
            assertTrue(status.output().contains(service + " running pid=" + markedProcess.pid()), status.output());

            ProcessResult stop = runScript(validEnvFile(), "stop");

            assertEquals(0, stop.exitCode(), stop.output());
            assertTrue(stop.output().contains("Stopped " + service + " pid=" + markedProcess.pid()), stop.output());
            markedProcess.waitFor(5, TimeUnit.SECONDS);
            assertFalse(markedProcess.isAlive(), stop.output());
            assertFalse(Files.exists(pidFile), stop.output());
            assertFalse(Files.exists(metaFile), stop.output());
        } finally {
            markedProcess.destroyForcibly();
            markedProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopKeepsPidFileWhenMatchingProcessIgnoresTerm() throws Exception {
        Path root = repositoryRoot();
        Path pidDir = localPidDir();
        String service = "api-service-app";
        String token = "test-token-ignore-term";
        String marker = "mochat-local:" + service + ":" + root + ":" + token;
        ProcessBuilder processBuilder = new ProcessBuilder(
            "bash",
            "-c",
            "exec -a \"$MOCHAT_TEST_MARKER :api-service-app:run\" bash -c 'trap \"\" TERM; while true; do sleep 1; done'"
        );
        processBuilder.environment().put("MOCHAT_TEST_MARKER", marker);
        Process stubbornProcess = processBuilder.start();
        try {
            Files.createDirectories(pidDir);
            Path pidFile = pidDir.resolve(service + ".pid");
            Path metaFile = pidDir.resolve(service + ".meta");
            Files.writeString(pidFile, Long.toString(stubbornProcess.pid()));
            Files.writeString(metaFile, "token=" + token + "\n");

            ProcessResult stop = runScript(validEnvFile(), "stop");

            assertEquals(1, stop.exitCode(), stop.output());
            assertTrue(stop.output().contains(service + " did not stop after TERM pid=" + stubbornProcess.pid()), stop.output());
            assertTrue(stubbornProcess.isAlive(), stop.output());
            assertTrue(Files.exists(pidFile), stop.output());
            assertTrue(Files.exists(metaFile), stop.output());
        } finally {
            stubbornProcess.destroyForcibly();
            stubbornProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private List<String> gradleCommandLines(String output) {
        return output.lines()
            .map(String::trim)
            .filter(line -> line.contains("./gradlew"))
            .toList();
    }

    private Path validEnvFile() throws IOException {
        Path envFile = tempDir.resolve("valid.env");
        Files.writeString(envFile, """
            MOCHAT_LIVEKIT_URL=ws://livekit.local
            MOCHAT_LIVEKIT_API_KEY=local-key
            MOCHAT_LIVEKIT_API_SECRET=local-secret
            """);
        return envFile;
    }

    private Path localPidDir() {
        return tempDir.resolve("local-pids");
    }

    private Path localLogDir() {
        return tempDir.resolve("local-logs");
    }

    private ProcessResult runScript(Path envFile, String command) throws Exception {
        Path root = repositoryRoot();
        ProcessBuilder builder = new ProcessBuilder("bash", "scripts/run-local.sh", command)
            .directory(root.toFile())
            .redirectErrorStream(true);
        builder.environment().put("MOCHAT_ENV_FILE", envFile.toString());
        builder.environment().remove("MOCHAT_LIVEKIT_URL");
        builder.environment().remove("MOCHAT_LIVEKIT_API_KEY");
        builder.environment().remove("MOCHAT_LIVEKIT_API_SECRET");
        builder.environment().put("MOCHAT_LOCAL_PID_DIR", localPidDir().toString());
        builder.environment().put("MOCHAT_LOCAL_LOG_DIR", localLogDir().toString());
        Process process = builder.start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("scripts/run-local.sh timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private Path repositoryRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IOException("Could not locate repository root");
        }
        return current;
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
