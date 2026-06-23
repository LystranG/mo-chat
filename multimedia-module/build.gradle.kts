plugins {
    `java-library`
}

dependencies {
    implementation(project(":common"))
    implementation(project(":protocol"))
    implementation(project(":logic-module"))
    
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut:micronaut-http:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")
    
    // S3 SDK for RustFS/S3 storage
    implementation("software.amazon.awssdk:s3:2.25.0")
    
    // FFmpeg wrapper for audio/video/image processing
    implementation("ws.schild:jave-all-deps:3.3.1")
    
    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    
    // Micronaut Test Framework
    testImplementation("io.micronaut.test:micronaut-test-junit5:4.6.0")
    testImplementation("io.micronaut:micronaut-http-client:4.9.0")
    
    // Mockito for mocking dependencies
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
    
    // Testcontainers (if needed for database tests)
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")

    //为了集成测试，测试用的minio，功能上还是rustfs
    // Testcontainers modules
    testImplementation("org.testcontainers:minio:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    
    // PostgreSQL JDBC driver for tests
    testImplementation("org.postgresql:postgresql:42.7.7")
}

tasks.test {
    useJUnitPlatform()
}