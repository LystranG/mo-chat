import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

plugins {
    id("org.graalvm.buildtools.native") version "0.11.1" apply false
}

val deployableNativeAppImages = mapOf(
    ":app" to "mo-chat",
    ":access-gateway-app" to "access-gateway",
    ":api-service-app" to "api-service",
    ":message-service-app" to "message-service",
    ":persistence-service-app" to "persistence-service",
)

allprojects {
    group = "com.github.lystran"
    version = "1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
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

configure(deployableNativeAppImages.keys.map(::project)) {
    plugins.withId("application") {
        pluginManager.apply("org.graalvm.buildtools.native")

        dependencies {
            add("annotationProcessor", "io.micronaut:micronaut-graal:4.9.0")
        }

        extensions.configure<GraalVMExtension>("graalvmNative") {
            binaries.named("main") {
                imageName.set(deployableNativeAppImages.getValue(path))
                resources.autodetect()
                buildArgs.add("-H:+SharedArenaSupport")
                buildArgs.add("-Ob")
            }
        }

        tasks.named<BuildNativeImageTask>("nativeCompile") {
            doFirst("skipWhenNativeImageIsMissing") {
                val configuredJavaHome = options.get().javaLauncher.orNull
                    ?.metadata
                    ?.installationPath
                    ?.asFile

                val candidateHomes = mutableListOf<File>()
                configuredJavaHome?.let(candidateHomes::add)
                System.getenv("GRAALVM_HOME")?.takeIf { it.isNotBlank() }?.let(::File)?.let(candidateHomes::add)
                System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)?.let(candidateHomes::add)
                candidateHomes.add(File(System.getProperty("java.home")))

                val nativeImageExecutable = candidateHomes
                    .asSequence()
                    .flatMap { home ->
                        sequenceOf(
                            home.resolve("bin/native-image"),
                            home.resolve("bin/native-image.cmd"),
                            home.resolve("bin/native-image.exe"),
                        )
                    }
                    .firstOrNull { it.isFile }

                if (nativeImageExecutable == null) {
                    logger.lifecycle(
                        "Skipping ${project.path}:nativeCompile: native-image is unavailable in javaLauncher/GRAALVM_HOME/JAVA_HOME/java.home."
                    )
                    throw StopExecutionException("native-image unavailable")
                }
            }
        }
    }
}

private fun podmanSocketFromRuntimeDir(): Path? {
    val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf(String::isNotBlank) ?: return null
    val socket = Path.of(runtimeDir, "podman", "podman.sock")
    return socket.takeIf(Files::exists)
}
