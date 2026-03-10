plugins {
    application
}

dependencies {
    implementation(project(":service-runtime"))
    implementation(project(":common"))
    implementation(project(":logic-module"))
    implementation(project(":protocol"))
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut.grpc:micronaut-grpc-runtime:4.9.0")
    implementation("io.micronaut.grpc:micronaut-grpc-client-runtime:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    implementation("io.micronaut:micronaut-http-server-netty:4.9.0")
    implementation("io.micronaut:micronaut-jackson-databind:4.9.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("org.yaml:snakeyaml:2.4")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.micronaut.grpc:micronaut-grpc-client-runtime:4.9.0")
    testImplementation("io.micronaut:micronaut-runtime:4.9.0")
    testImplementation("io.micronaut.test:micronaut-test-junit5:4.7.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.github.lystran.mochat.apiservice.ApiServiceApplication")
}

tasks.test {
    useJUnitPlatform()
}
