plugins {
    application
}

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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.github.lystran.mochat.callservice.CallServiceApplication")
}

tasks.test {
    useJUnitPlatform()
}
