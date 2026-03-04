plugins {
    `java-library`
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut:micronaut-http:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.1")
    implementation("commons-codec:commons-codec:1.17.2")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.micronaut:micronaut-http-client:4.9.0")
    testImplementation("io.micronaut:micronaut-http-server-netty:4.9.0")
    testImplementation("io.micronaut:micronaut-jackson-databind:4.9.0")
    testImplementation("io.micronaut.test:micronaut-test-junit5:4.7.0")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
