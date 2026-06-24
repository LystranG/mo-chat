# syntax=docker/dockerfile:1.7
FROM ghcr.1ms.run/graalvm/native-image-community:25 AS builder

WORKDIR /workspace
ENV GRADLE_USER_HOME=/workspace/.gradle-cache

COPY . .

RUN --mount=type=cache,target=/workspace/.gradle-cache \
    chmod +x ./gradlew \
    && ./gradlew --no-daemon :persistence-service-app:installDist

FROM docker.io/eclipse-temurin:25-jre

WORKDIR /app

COPY --from=builder --chown=65532:65532 /workspace/persistence-service-app/build/install/persistence-service-app /app

USER 65532:65532

ENTRYPOINT ["/app/bin/persistence-service-app"]
