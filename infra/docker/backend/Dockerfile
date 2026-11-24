# Dokene backend image. Build context: ./backend (+ additional contexts "entrypoint" = infra/docker/backend and
# "java4shell" = scripts)
# Build stage uses the JDK declared by the Gradle toolchain (Java 27); runtime stage ships only a JRE.
#
# Java4Shell (CVE-2026-114938, issues 429 and 432): both digests are official images that ship the vendor-fixed Temurin
# 27.0.1.1 build on linux/amd64 and linux/arm64. Each stage runs scripts/check-java4shell-runtime.sh --require-patched
# (bound from the additional build context "java4shell" = scripts), so the build fails on any platform whose JDK or JRE
# is not a fixed build, for example after a digest bump to an unfixed image. With a fixed build guaranteed at build
# time, no JVM needs the interim -XX:-UseBASE64Intrinsics mitigation.

FROM eclipse-temurin:27-jdk@sha256:f67511a7888677dbc4fb64ee6f17f9237c24d2b110f65a61716a3b516b6b3fa8 AS build
WORKDIR /workspace
# The Gradle and compiler JVMs must run a Java4Shell-fixed JDK.
RUN --mount=type=bind,from=java4shell,source=check-java4shell-runtime.sh,target=/tmp/check-java4shell-runtime.sh \
    bash /tmp/check-java4shell-runtime.sh --require-patched

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

FROM eclipse-temurin:27-jre@sha256:8f8cfda6393f63a029dbe83ceda4c19a31d268184662e96d9641f57afdaf870b AS runtime

# Fail the build unless this platform's JRE is a Java4Shell-fixed build.
RUN --mount=type=bind,from=java4shell,source=check-java4shell-runtime.sh,target=/tmp/check-java4shell-runtime.sh \
    bash /tmp/check-java4shell-runtime.sh --require-patched

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
