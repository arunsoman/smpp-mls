FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /app
COPY . .
RUN ./gradlew nativeCompile --no-daemon

FROM gcr.io/distroless/base-debian12
COPY --from=builder /app/build/native/nativeCompile/smpp-mls /app
ENTRYPOINT ["/app"]
