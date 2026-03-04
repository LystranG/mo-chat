plugins {
    `java-library`
}

dependencies {
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.postgresql:postgresql:42.7.5")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.testcontainers:junit-jupiter:1.20.5")
    testImplementation("org.testcontainers:postgresql:1.20.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
