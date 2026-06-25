# syntax=docker/dockerfile:1.7
FROM ghcr.1ms.run/graalvm/native-image-community:25 AS builder

WORKDIR /workspace
ENV GRADLE_USER_HOME=/workspace/.gradle-cache

COPY . .

RUN --mount=type=cache,target=/workspace/.gradle-cache \
    chmod +x ./gradlew \
    && ./gradlew --no-daemon :persistence-service-app:nativeCompile

FROM gcr.1ms.run/distroless/cc

WORKDIR /app

COPY --from=builder --chown=65532:65532 /workspace/persistence-service-app/build/native/nativeCompile/persistence-service /app/persistence-service

USER 65532:65532

ENTRYPOINT ["/app/persistence-service"]
