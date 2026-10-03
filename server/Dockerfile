# Erwartet die fertige Distribution aus `./gradlew :server:installDist` (siehe .github/workflows/server.yml).
FROM denoland/deno:bin-2.9.7 AS deno

FROM eclipse-temurin:21-jre-noble

ARG YTDLP_VERSION=2026.08.19

# ffmpeg: Audio extrahieren und Thumbnails konvertieren; deno: JS-Runtime, die yt-dlp für YouTube braucht.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL -o /usr/local/bin/yt-dlp \
      "https://github.com/yt-dlp/yt-dlp/releases/download/${YTDLP_VERSION}/yt-dlp_linux" \
 && chmod 755 /usr/local/bin/yt-dlp
COPY --from=deno /deno /usr/local/bin/deno

RUN install -d -o 1000 -g 1000 /data /media
COPY build/install/asmr-server /opt/asmr-server

ENV ASMR_PORT=8080 \
    ASMR_DATA_DIR=/data \
    ASMR_MEDIA_DIR=/media \
    XDG_CACHE_HOME=/data/.cache \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 --enable-native-access=ALL-UNNAMED"

USER 1000:1000
EXPOSE 8080
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fs http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/opt/asmr-server/bin/asmr-server"]
