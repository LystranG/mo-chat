FROM ghcr.1ms.run/graalvm/native-image-community:25 AS builder

WORKDIR /workspace

COPY . .

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon :api-service-app:nativeCompile

FROM gcr.1ms.run/distroless/cc

WORKDIR /app

COPY --from=builder --chown=65532:65532 /workspace/api-service-app/build/native/nativeCompile/api-service /app/api-service

USER 65532:65532

EXPOSE 8080 19091

ENTRYPOINT ["/app/api-service"]
