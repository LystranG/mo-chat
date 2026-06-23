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
}

tasks.test {
    useJUnitPlatform()
}