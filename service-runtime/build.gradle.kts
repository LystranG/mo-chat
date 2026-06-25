plugins {
    `java-library`
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")

    // 分布式链路追踪：OpenTelemetry 自动埋点 HTTP + gRPC，OTLP 导出到 Tempo。
    // 由 service-runtime 统一引入，所有 *-app 通过依赖传递获得运行时能力。
    // 开关由 OTEL_SDK_DISABLED / OTEL_TRACES_EXPORTER 环境变量控制（见 Helm observability 配置）。
    api("io.micronaut.tracing:micronaut-tracing-opentelemetry-http:7.1.2")
    api("io.micronaut.tracing:micronaut-tracing-opentelemetry-grpc:7.1.2")
    api("io.micronaut.tracing:micronaut-tracing-annotation:7.1.2")
    // OTLP exporter 默认用 OkHttp 发送器，它传递引入 Kotlin 协程，
    // 与 GraalVM native image 不兼容（kotlin.coroutines.intrinsics.CoroutineSingletons
    // 无法在 build-time 初始化）。改用 JDK HttpClient 发送器避开 Kotlin/OkHttp。
    api("io.opentelemetry:opentelemetry-exporter-otlp:1.51.0") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    api("io.opentelemetry:opentelemetry-exporter-sender-jdk:1.51.0")
    api("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.17.1-alpha")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.yaml:snakeyaml:2.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
