# Dokene backend image. Build context: ./backend (+ additional context "entrypoint" = infra/docker/backend)
# Build stage uses the JDK declared by the Gradle toolchain (Java 27); runtime stage ships only a JRE.
#
# Java4Shell (CVE-2026-114938, issue 429): when these digests were resolved (2026-11-20) the newest eclipse-temurin:27
# images held 27.0.1, which predates the vendor fix 27.0.1.1, and Adoptium had published the fixed 27.0.1.1+4 JRE for
# x64 only. So on linux/amd64 the runtime stage replaces the image's JRE with that vendor build, fetched by URL and
# checked against its published SHA-256 (stage jre-fixed-x64). linux/arm64, which the advisories do not list as
# affected, keeps the image's JRE; any other target platform fails the build. Every JVM also runs with -XX:-UseBASE64Intrinsics (here, in entrypoint.sh
# and in the compose `migrate` entrypoint): the build stage JDK and the arm64 JRE are unfixed builds. Once the official
# images hold 27.0.1.1 or later for both platforms, bump the digests and drop the jre-fixed-x64 stage and the JRE swap.
# scripts/check-java4shell-runtime.sh reports which case applies.

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

# Vendor-fixed Temurin JRE for x64 (Adoptium release jdk-27.0.1.1+4; checksum from the Adoptium API, equal to the
# .sha256.txt published beside the archive). ADD verifies the checksum before the archive is used. This stage only
# unpacks (tar runs on any build platform); the runtime stage decides by TARGETARCH whether to use it.
FROM eclipse-temurin:27-jre@sha256:acfb350b50e707addf3dcbb47f7052d1882d1e5f1670ce838a9c7838d82d4fe5 AS jre-fixed-x64
ADD --checksum=sha256:9e15835ca3451a0089aac2c3667c009bc2743b9cb15030a14c6f5ec05f8d5aaf \
    https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27.0.1.1%2B4/OpenJDK27U-jre_x64_linux_hotspot_27.0.1.1_4.tar.gz \
    /tmp/temurin-jre.tar.gz
RUN mkdir -p /jre \
    && tar -xzf /tmp/temurin-jre.tar.gz -C /jre --strip-components=1 --no-same-owner \
    && rm /tmp/temurin-jre.tar.gz

FROM eclipse-temurin:27-jre@sha256:acfb350b50e707addf3dcbb47f7052d1882d1e5f1670ce838a9c7838d82d4fe5 AS runtime

# On linux/amd64, replace the image's JRE wholesale with the fixed one, so no file of the unfixed build is left, and
# fail the build unless it reports 27.0.1.1. No fixed aarch64 build existed yet: linux/arm64 keeps the image's JRE.
ARG TARGETARCH
RUN --mount=type=bind,from=jre-fixed-x64,source=/jre,target=/mnt/jre-fixed \
    case "$TARGETARCH" in \
        amd64) rm -rf /opt/java/openjdk \
            && cp -a /mnt/jre-fixed /opt/java/openjdk \
            && /opt/java/openjdk/bin/java -XshowSettings:properties -version 2>&1 \
                | grep -q 'java.runtime.version = 27\.0\.1\.1+' ;; \
        arm64) : ;; \
        *) echo "Unsupported TARGETARCH '$TARGETARCH': no Java4Shell decision for this platform" >&2; exit 1 ;; \
    esac

RUN groupadd --system --gid 10001 dokene \
    && useradd --system --uid 10001 --gid dokene --home-dir /app --shell /usr/sbin/nologin dokene
WORKDIR /app

COPY --from=build --chown=10001:10001 /workspace/dist/dokene.jar /app/dokene.jar
# `entrypoint` is an additional build context (infra/docker/backend) declared in compose.yaml.
COPY --from=entrypoint --chown=10001:10001 --chmod=0555 entrypoint.sh /app/entrypoint.sh

USER 10001:10001
EXPOSE 8080

# -XX:-UseBASE64Intrinsics is the Java4Shell mitigation (see the header). It is also on the java command line in
# entrypoint.sh, so overriding JAVA_TOOL_OPTIONS at deploy time cannot drop it from the application JVM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:-UseBASE64Intrinsics"

HEALTHCHECK --interval=5s --timeout=5s --retries=30 --start-period=30s \
    CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /api/session HTTP/1.0\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 401 '"]

ENTRYPOINT ["/app/entrypoint.sh"]
