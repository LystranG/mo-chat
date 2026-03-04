import org.gradle.api.tasks.StopExecutionException
import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import java.io.File

plugins {
    application
    id("org.graalvm.buildtools.native") version "0.11.1"
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    annotationProcessor("io.micronaut:micronaut-graal:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("org.yaml:snakeyaml:2.4")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.github.lystran.mochat.Application")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("mo-chat")
            buildArgs.add("-Ob")
        }
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
                    home.resolve("bin/native-image.exe")
                )
            }
            .firstOrNull { it.isFile }

        if (nativeImageExecutable == null) {
            logger.lifecycle(
                "Skipping :app:nativeCompile: native-image is unavailable in javaLauncher/GRAALVM_HOME/JAVA_HOME/java.home."
            )
            throw StopExecutionException("native-image unavailable")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
