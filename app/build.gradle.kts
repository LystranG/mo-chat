plugins {
    application
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation(project(":common"))
    implementation(project(":message-module"))
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
    implementation("org.apache.rocketmq:rocketmq-client:5.4.0") {
        exclude(group = "io.grpc", module = "grpc-stub")
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
        exclude(group = "io.opentelemetry")
        exclude(group = "com.squareup.okio")
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

tasks.test {
    useJUnitPlatform()
}
