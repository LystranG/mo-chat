plugins {
    application
}

val nettyVersion = "4.1.108.Final"

dependencies {
    implementation(project(":service-runtime"))
    implementation(project(":common"))
    implementation(project(":connection-module"))
    implementation(project(":infra-redis"))
    implementation(project(":protocol"))
    implementation("io.netty:netty-handler:$nettyVersion")
    implementation("io.netty:netty-transport:$nettyVersion")
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut.grpc:micronaut-grpc-runtime:4.9.0")
    implementation("io.micronaut.grpc:micronaut-grpc-client-runtime:4.9.0")
    implementation("io.micronaut:micronaut-http-server-netty:4.9.0")
    implementation("io.micronaut:micronaut-jackson-databind:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("org.yaml:snakeyaml:2.4")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("io.micronaut:micronaut-http-client:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.micronaut:micronaut-runtime:4.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.github.lystran.mochat.accessgateway.AccessGatewayApplication")
}

tasks.test {
    useJUnitPlatform()
}
