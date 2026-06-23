pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "mo-chat"

include(
    "app",
    "service-runtime",
    "access-gateway-app",
    "api-service-app",
    "message-service-app",
    "persistence-service-app",
    "call-service-app",
    "multimedia-service-app",
    "common",
    "protocol",
    "infra-redis",
    "connection-module",
    "message-module",
    "logic-module",
    "persistence-module",
    "call-module",
    "multimedia-module"
)

