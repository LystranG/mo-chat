import org.gradle.api.tasks.StopExecutionException
import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import java.io.File

plugins {
    application
    id("org.graalvm.buildtools.native") version "0.11.1"
}

dependencies {
    // 统一 Netty 版本，避免 lettuce/rocketmq 带入的旧版本与 Micronaut 4.2.x 冲突
    implementation(platform("io.netty:netty-bom:4.2.2.Final"))

    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    annotationProcessor("io.micronaut:micronaut-graal:4.9.0")
    implementation(project(":common"))
    implementation(project(":message-module"))
    implementation(project(":call-module"))
    implementation(project(":logic-module"))
    implementation(project(":connection-module"))
    implementation(project(":infra-redis"))
    implementation(project(":persistence-module"))
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    implementation("io.micronaut:micronaut-http-server-netty:4.9.0")
    implementation("io.micronaut:micronaut-jackson-databind:4.9.0")
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("org.apache.rocketmq:rocketmq-client:5.3.2") {
        exclude(group = "io.grpc", module = "grpc-stub")
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
        exclude(group = "io.opentelemetry")
        exclude(group = "com.squareup.okio")
        exclude(group = "io.netty", module = "netty-all")
    }
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("org.yaml:snakeyaml:2.4")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation(project(":common"))
    testImplementation("org.mockito:mockito-core:5.18.0")
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
