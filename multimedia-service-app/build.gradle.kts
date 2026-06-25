plugins {
    application
}

dependencies {
    implementation(project(":service-runtime"))
    implementation(project(":common"))
    implementation(project(":multimedia-module"))
    implementation(project(":logic-module"))
    implementation(project(":message-module"))
    implementation("io.micronaut:micronaut-http-server-netty:4.9.0")
    implementation("io.micronaut:micronaut-jackson-databind:4.9.0")
    implementation("io.micronaut:micronaut-management:4.9.0")
    implementation("io.micronaut.micrometer:micronaut-micrometer-registry-prometheus:5.12.0")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("org.yaml:snakeyaml:2.4")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.micronaut:micronaut-runtime:4.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.github.lystran.mochat.multimediaservice.MultimediaServiceApplication")
}

tasks.test {
    useJUnitPlatform()
}
