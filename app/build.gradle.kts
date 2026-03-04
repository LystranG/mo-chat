plugins {
    application
    id("org.graalvm.buildtools.native") version "0.11.1"
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    annotationProcessor("io.micronaut:micronaut-graal:4.9.0")
    implementation("io.micronaut:micronaut-runtime:4.9.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("org.yaml:snakeyaml:2.4")

    testAnnotationProcessor("io.micronaut:micronaut-inject-java:4.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.github.lystran.mochat.Application")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("mo-chat")
            buildArgs.add("-Ob")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
