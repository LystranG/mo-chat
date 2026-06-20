plugins {
    `java-library`
}

dependencies {
    implementation(project(":common"))
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut:micronaut-http:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    implementation("io.micronaut:micronaut-websocket:4.9.0")
    implementation("io.micronaut:micronaut-jackson-databind:4.9.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    implementation("io.livekit:livekit-server:0.12.1")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.baomidou:mybatis-plus-core:3.5.10.1")
    implementation("org.mybatis:mybatis:3.5.16")
    implementation("org.apache.rocketmq:rocketmq-client:5.3.2") {
        exclude(group = "io.grpc", module = "grpc-stub")
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
        exclude(group = "io.opentelemetry")
        exclude(group = "com.squareup.okio")
        exclude(group = "io.netty", module = "netty-all")
    }

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
