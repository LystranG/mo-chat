plugins {
    `java-library`
}

val nettyVersion = "4.1.108.Final"

dependencies {
    implementation(project(":common"))
    implementation(project(":protocol"))

    implementation("io.netty:netty-codec:$nettyVersion")
    implementation("io.netty:netty-handler:$nettyVersion")
    implementation("io.netty:netty-transport:$nettyVersion")
    implementation("io.netty:netty-transport-classes-epoll:$nettyVersion")
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")
    runtimeOnly("io.netty.incubator:netty-incubator-transport-native-io_uring:0.0.26.Final:linux-x86_64")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
