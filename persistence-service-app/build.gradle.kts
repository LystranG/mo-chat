plugins {
    application
}

dependencies {
    implementation(project(":service-runtime"))
    implementation(project(":message-module"))
    implementation(project(":persistence-module"))
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("org.yaml:snakeyaml:2.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.micronaut:micronaut-runtime:4.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.github.lystran.mochat.persistenceservice.PersistenceServiceApplication")
}

tasks.test {
    useJUnitPlatform()
}
