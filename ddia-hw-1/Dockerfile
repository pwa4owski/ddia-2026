FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew --no-daemon test bootJar

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S booking && adduser -S -G booking booking
WORKDIR /app
COPY --from=build --chown=booking:booking /workspace/build/libs/booking-service-*.jar app.jar
USER booking
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
