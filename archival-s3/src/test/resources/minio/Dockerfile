# MinIO community images were removed from public registries. Build the official
# source-only release for integration tests instead of depending on a third-party mirror.
# https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z
FROM golang:1.24.8-bookworm AS build
RUN CGO_ENABLED=0 GOBIN=/out go install -ldflags="-s -w" github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z

FROM alpine:3.22.2
COPY --from=build /out/minio /usr/local/bin/minio
EXPOSE 9000
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data"]
