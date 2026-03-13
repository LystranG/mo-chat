FROM ghcr.1ms.run/graalvm/native-image-community:25 AS builder

WORKDIR /workspace

COPY . .

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon :message-service-app:nativeCompile

FROM gcr.1ms.run/distroless/cc

WORKDIR /app

COPY --from=builder --chown=65532:65532 /workspace/message-service-app/build/native/nativeCompile/message-service /app/message-service

USER 65532:65532

EXPOSE 19092

ENTRYPOINT ["/app/message-service"]
