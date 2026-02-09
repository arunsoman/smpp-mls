FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN microdnf install -y findutils
RUN ./gradlew nativeCompile --no-daemon

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y zlib1g && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/build/native/nativeCompile/smpp-mls smpp-mls
ENTRYPOINT ["/app/smpp-mls"]
