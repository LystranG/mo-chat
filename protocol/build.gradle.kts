plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.5"
}

val protobufVersion = "4.30.2"

dependencies {
    api("com.google.protobuf:protobuf-java:$protobufVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}

tasks.test {
    useJUnitPlatform()
}
