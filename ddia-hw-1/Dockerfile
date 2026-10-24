FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY http-metrics-spring-boot-starter/build.gradle.kts ./http-metrics-spring-boot-starter/
COPY http-metrics-spring-boot-starter/src ./http-metrics-spring-boot-starter/src
COPY dashboard-generator/build.gradle.kts ./dashboard-generator/
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew --no-daemon :test :bootJar

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S booking && adduser -S -G booking booking
WORKDIR /app
COPY --from=build --chown=booking:booking /workspace/build/libs/booking-service-*.jar app.jar
USER booking
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM build AS generator-build
COPY dashboard-generator/src ./dashboard-generator/src
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew --no-daemon :dashboard-generator:installDist

FROM eclipse-temurin:25-jre-alpine AS generator
RUN addgroup -S generator && adduser -S -u 10001 -G generator generator
WORKDIR /app
COPY --from=generator-build --chown=generator:generator /workspace/dashboard-generator/build/install/dashboard-generator/ ./
USER generator
ENTRYPOINT ["/app/bin/dashboard-generator"]
