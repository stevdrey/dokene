# Dokene backend image. Build context: ./backend (+ additional context "entrypoint" = infra/docker/backend)
# Build stage uses the JDK declared by the Gradle toolchain (Java 27); runtime stage ships only a JRE.
#
# Java4Shell (CVE-2026-114938, issue 429): when these digests were resolved (2026-11-20) the newest eclipse-temurin:27
# images held 27.0.1, which predates the vendor fix 27.0.1.1. Every JVM this image starts therefore runs with
# -XX:-UseBASE64Intrinsics (here, in entrypoint.sh and in the compose `migrate` entrypoint) until the digests are
# bumped to an image that reports 27.0.1.1 or later. scripts/check-java4shell-runtime.sh reports which case applies.

FROM eclipse-temurin:27-jdk@sha256:5e6d9093c7a8e95adfaa8828253c58f2c6820116d07a062440a778358807578a AS build
WORKDIR /workspace
# The Gradle and compiler JVMs of the build stage get the same interim mitigation as the runtime.
ENV JAVA_TOOL_OPTIONS="-XX:-UseBASE64Intrinsics"

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

FROM eclipse-temurin:27-jre@sha256:acfb350b50e707addf3dcbb47f7052d1882d1e5f1670ce838a9c7838d82d4fe5 AS runtime

RUN groupadd --system --gid 10001 dokene \
    && useradd --system --uid 10001 --gid dokene --home-dir /app --shell /usr/sbin/nologin dokene
WORKDIR /app

COPY --from=build --chown=10001:10001 /workspace/dist/dokene.jar /app/dokene.jar
# `entrypoint` is an additional build context (infra/docker/backend) declared in compose.yaml.
COPY --from=entrypoint --chown=10001:10001 --chmod=0555 entrypoint.sh /app/entrypoint.sh

USER 10001:10001
EXPOSE 8080

# -XX:-UseBASE64Intrinsics is the Java4Shell interim mitigation (see the header). It is also on the java command line in
# entrypoint.sh, so overriding JAVA_TOOL_OPTIONS at deploy time cannot drop it from the application JVM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:-UseBASE64Intrinsics"

HEALTHCHECK --interval=5s --timeout=5s --retries=30 --start-period=30s \
    CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /api/session HTTP/1.0\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 401 '"]

ENTRYPOINT ["/app/entrypoint.sh"]
