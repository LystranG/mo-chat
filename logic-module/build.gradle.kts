plugins {
    `java-library`
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    implementation("io.micronaut:micronaut-http:4.9.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.1")
    implementation("commons-codec:commons-codec:1.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
