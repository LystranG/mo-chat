group = "com.github.lystran"
version = "1.0-SNAPSHOT"

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withId("java") {
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
            toolchain {
                languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(25))
            }
        }
    }
}
