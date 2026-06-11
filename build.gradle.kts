import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.nio.file.Files
import java.nio.file.Path

allprojects {
    group = "com.github.lystran"
    version = "1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<Test>().configureEach {
        if (System.getenv("DOCKER_HOST").isNullOrBlank() && !Files.exists(Path.of("/var/run/docker.sock"))) {
            podmanSocketFromRuntimeDir()?.let { podmanSocket ->
                environment("DOCKER_HOST", "unix://${podmanSocket.toAbsolutePath()}")
            }
        }
    }
}

private fun podmanSocketFromRuntimeDir(): Path? {
    val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf(String::isNotBlank) ?: return null
    val socket = Path.of(runtimeDir, "podman", "podman.sock")
    return socket.takeIf(Files::exists)
}
