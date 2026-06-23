plugins {
    application
}

val jacksonVersion = "2.18.3"

dependencies {
    implementation(project(":service-runtime"))
    implementation(project(":common"))
    implementation(project(":infra-redis"))
    implementation(project(":call-module"))
    implementation(project(":persistence-module"))
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    implementation("io.micronaut:micronaut-http-server-netty:4.9.0")
    implementation("io.micronaut:micronaut-jackson-databind:4.9.0")
    implementation("io.micronaut:micronaut-websocket:4.9.0")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
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
    testImplementation("io.micronaut:micronaut-runtime:4.9.0")
    testImplementation("io.micronaut:micronaut-http-client:4.9.0")
    testImplementation("io.micronaut:micronaut-http-server-netty:4.9.0")
    testImplementation("io.micronaut:micronaut-jackson-databind:4.9.0")
    testImplementation("io.micronaut.test:micronaut-test-junit5:4.7.0")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mybatis:mybatis:3.5.16")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")

    constraints {
        listOf(
            "com.fasterxml.jackson.core:jackson-databind",
            "com.fasterxml.jackson.core:jackson-core",
            "com.fasterxml.jackson.core:jackson-annotations",
            "com.fasterxml.jackson.datatype:jackson-datatype-jdk8",
            "com.fasterxml.jackson.datatype:jackson-datatype-jsr310",
            "com.fasterxml.jackson.dataformat:jackson-dataformat-toml",
        ).forEach { module ->
            implementation(module) {
                version {
                    strictly(jacksonVersion)
                }
                because("Micronaut 4.9 native metadata expects the Jackson 2.18 API surface.")
            }
        }
    }
}

application {
    mainClass.set("com.github.lystran.mochat.callservice.CallServiceApplication")
}

tasks.test {
    useJUnitPlatform()
}

pluginManager.withPlugin("org.graalvm.buildtools.native") {
    extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension>("graalvmNative") {
        binaries.named("main") {
            buildArgs.add("--initialize-at-build-time=kotlin.coroutines.intrinsics.CoroutineSingletons")
        }
    }
}
