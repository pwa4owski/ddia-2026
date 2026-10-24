plugins {
    application
}

group = "ru.ddia"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.grafana:grafana-foundation-sdk:v0.0.20")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.1")
    implementation("org.jcommander:jcommander:3.0")
}

application {
    mainClass = "ru.ddia.dashboard.DashboardGenerator"
}
