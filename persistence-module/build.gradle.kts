plugins {
    `java-library`
}

dependencies {
    implementation(project(":message-module"))
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.1")
    implementation("org.apache.rocketmq:rocketmq-client:5.3.2") {
        exclude(group = "io.grpc", module = "grpc-stub")
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
        exclude(group = "io.opentelemetry")
        exclude(group = "com.squareup.okio")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.testcontainers:junit-jupiter:1.20.5")
    testImplementation("org.testcontainers:postgresql:1.20.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
