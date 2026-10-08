# syntax=docker/dockerfile:1
# Dokene backend image. Build context: ./backend (+ additional context "entrypoint" = infra/docker/backend)
# Build stage uses the JDK declared by the Gradle toolchain (Java 26); runtime stage ships only a JRE.

FROM eclipse-temurin:26-jdk AS build
WORKDIR /workspace

# Resolve the Gradle distribution first so this layer is cached independently of the sources.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --version

COPY config config
COPY src src
# Tests run in CI (`./gradlew test`); the image build only packages the application.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
    && mkdir -p /workspace/dist \
    && cp build/libs/*.jar /workspace/dist/dokene.jar \
    && rm -f /workspace/dist/*-plain.jar

FROM eclipse-temurin:26-jre AS runtime

RUN groupadd --system --gid 10001 dokene \
    && useradd --system --uid 10001 --gid dokene --home-dir /app --shell /usr/sbin/nologin dokene
WORKDIR /app

COPY --from=build --chown=10001:10001 /workspace/dist/dokene.jar /app/dokene.jar
# `entrypoint` is an additional build context (infra/docker/backend) declared in compose.yaml.
COPY --from=entrypoint --chown=10001:10001 --chmod=0555 entrypoint.sh /app/entrypoint.sh

USER 10001:10001
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=5s --timeout=5s --retries=30 --start-period=30s \
    CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /api/session HTTP/1.0\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 401 '"]

ENTRYPOINT ["/app/entrypoint.sh"]
