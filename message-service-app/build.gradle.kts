plugins {
    application
}

dependencies {
    implementation(project(":service-runtime"))
    implementation(project(":common"))
    implementation(project(":infra-redis"))
    implementation(project(":logic-module"))
    implementation(project(":message-module"))
    implementation(project(":protocol"))
    implementation("org.apache.rocketmq:rocketmq-client:5.3.2") {
        exclude(group = "io.grpc", module = "grpc-stub")
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
        exclude(group = "io.opentelemetry")
        exclude(group = "com.squareup.okio")
    }
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut.grpc:micronaut-grpc-runtime:4.9.0")
    implementation("io.micronaut.grpc:micronaut-grpc-client-runtime:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("org.yaml:snakeyaml:2.4")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.micronaut:micronaut-runtime:4.9.0")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation(project(":access-gateway-app"))
    testImplementation(project(":connection-module"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.github.lystran.mochat.messageservice.MessageServiceApplication")
}

tasks.test {
    useJUnitPlatform()
}
