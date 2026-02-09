FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN microdnf install -y findutils
RUN ./gradlew nativeCompile --no-daemon

FROM gcr.io/distroless/base-debian12
WORKDIR /app
COPY --from=builder /app/build/native/nativeCompile/smpp-mls smpp-mls
ENTRYPOINT ["/app/smpp-mls"]
