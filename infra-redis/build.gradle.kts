plugins {
    `java-library`
}

dependencies {
    implementation(project(":common"))
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.testcontainers:junit-jupiter:1.20.5")
    testImplementation("org.testcontainers:testcontainers:1.20.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
