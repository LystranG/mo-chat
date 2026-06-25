plugins {
    `java-library`
}

dependencies {
    implementation(project(":common"))
    implementation(project(":message-module"))
    implementation(project(":protocol"))
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut:micronaut-http:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")
    implementation("org.apache.rocketmq:rocketmq-client:5.4.0") {
        exclude(group = "io.grpc", module = "grpc-stub")
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
        exclude(group = "io.opentelemetry")
        exclude(group = "com.squareup.okio")
    }
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.1")
    implementation("commons-codec:commons-codec:1.17.2")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.micronaut:micronaut-http-client:4.9.0")
    testImplementation("io.micronaut:micronaut-http-server-netty:4.9.0")
    testImplementation("io.micronaut:micronaut-jackson-databind:4.9.0")
    testImplementation("io.micronaut.test:micronaut-test-junit5:4.7.0")
    testImplementation(project(":connection-module"))
    testImplementation(project(":persistence-module"))
    testImplementation("org.flywaydb:flyway-core:10.20.1")
    testImplementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.postgresql:postgresql:42.7.5")
    testImplementation("org.testcontainers:junit-jupiter:1.20.5")
    testImplementation("org.testcontainers:postgresql:1.20.5")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
