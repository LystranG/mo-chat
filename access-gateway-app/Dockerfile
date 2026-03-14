FROM ghcr.1ms.run/graalvm/native-image-community:25 AS builder

WORKDIR /workspace

COPY . .

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon :access-gateway-app:nativeCompile

FROM gcr.1ms.run/distroless/cc

WORKDIR /app

COPY --from=builder --chown=65532:65532 /workspace/access-gateway-app/build/native/nativeCompile/access-gateway /app/access-gateway

USER 65532:65532

EXPOSE 18080 9000 19093

ENTRYPOINT ["/app/access-gateway"]
