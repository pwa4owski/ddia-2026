plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "booking-service"

include("http-metrics-spring-boot-starter")
include("dashboard-generator")
