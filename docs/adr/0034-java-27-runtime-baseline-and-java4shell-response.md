# ADR 0034: Java 27 Runtime Baseline and the Java4Shell Response

## Status

Accepted. Issue: [#429](https://github.com/stevdrey/dokene/issues/429), part of epic [#305](https://github.com/stevdrey/dokene/issues/305).

It replaces the Java 26 baseline named in [ADR 0015](0015-structured-next-best-action-recommendation-contracts.md) and [ADR 0022](0022-containerized-local-environment.md); those ADRs are otherwise unchanged.

## Context

Java4Shell (CVE-2026-114938), disclosed on 2026-11-17, is memory corruption in the HotSpot Base64 decoder intrinsic, reachable through `java.util.Base64.Decoder` on x86-64 hosts with AVX-512 VBMI. Vendors shipped fixes for 17, 21, 25 and 27. Java 26, the project's baseline, gets none: it no longer receives updates. The backend decodes client-supplied cursors with `Base64.getUrlDecoder()`, so the input is reachable. Details: [java4shell-cve-2026-114938.md](../security/java4shell-cve-2026-114938.md).

Two supported targets had a fixed build: Java 27 (27.0.1.1) and Java 25 LTS (25.0.5.1). On 2026-11-20 the official Temurin container images of both lines still held the unfixed build. The fixed Temurin 27 binaries were published for x64 (used by `setup-java`) but not yet for aarch64, which is why no official image existed.

## Decision

1. **Java 27 is the baseline.** The Gradle toolchain, CI and the backend image move from 26 to 27. Java 27 needs no source change (the full test suite passes) and keeps every language feature the code uses. Moving to 25 LTS would have removed features the code may already use and is a larger, separate decision.
2. **CI requires a fixed JDK.** Each `setup-java` step uses `check-latest: true`, so a cached older build on the runner is not reused, and the job then runs `scripts/check-java4shell-runtime.sh --require-patched`, which fails unless the JDK is a vendor-fixed build.
3. **The backend runtime ships the vendor fix where one exists, and every JVM runs mitigated.** On linux/amd64 the runtime stage replaces the image's JRE with the fixed Temurin `27.0.1.1+4` JRE, downloaded by URL with `ADD --checksum` (the SHA-256 published by Adoptium), and the build fails unless that JRE reports 27.0.1.1. linux/arm64, not listed as affected, keeps the official image's JRE. In addition, every JVM started from the backend image starts with `-XX:-UseBASE64Intrinsics`: on the java command line of `entrypoint.sh` and of the compose `migrate` service (an override of `JAVA_TOOL_OPTIONS` cannot remove it) and in `JAVA_TOOL_OPTIONS` (which covers other java invocations, including the Gradle build stage). The flag stays until the digests point at official images with a fixed build for both platforms; on the fixed x64 JRE it is defense in depth.
4. **Third-party Java images take the vendor fix.** Keycloak moves to `26.8.2-3`, which ships Red Hat OpenJDK 21.0.13.1. When a vendor image has no fix yet, the same flag goes into its JVM options (`JAVA_OPTS_APPEND` for Keycloak).
5. **One script answers "is this JVM exposed".** `scripts/check-java4shell-runtime.sh` reads the version, vendor, architecture and the effective `UseBASE64Intrinsics` value from the JVM itself and prints `PATCHED`, `NOT_AFFECTED`, `MITIGATED` or `VULNERABLE`. CI, the image verification and operators use it.

## Consequences

- On amd64 the backend image ships the vendor-fixed JRE. The arm64 image and the build stage JDK are unfixed builds with the vulnerable intrinsic off. The pinned x64 archive is a build input Dependabot does not update; it is replaced by the official image digest bump once both platforms are rebuilt (tracked in the security page). Superseded by the amendment below.
- Base64 decoding in the mitigated JVM runs in plain Java code: slower, but the backend decodes only short cursors. Superseded by the amendment below.
- CodeQL supports Java up to 26, so its job compiles with `--release 26` (`-PcodeqlJavaRelease=26`) on the fixed JDK 27 until CodeQL supports 27; the code cannot use a Java 27 language feature before then.
- Java 27 is not LTS. Its support ends when Java 28 ships; the baseline after that is a new decision.
- A version table of fixed builds lives in the script. A future advisory for the same bug means editing that table.

## Amendment: official fixed images (issue 432)

Issue [#432](https://github.com/stevdrey/dokene/issues/432) carried out the follow-up of decision 3 once the official `eclipse-temurin:27-jdk` and `27-jre` images held `27.0.1.1+4` for linux/amd64 and linux/arm64:

- Both digests point at those official images. The `jre-fixed-x64` stage, the per-platform JRE swap and the pinned archive are removed; the image pins are official digests only.
- Each stage of the backend image runs `scripts/check-java4shell-runtime.sh --require-patched` at build time (decision 5's script, bound from the build context `java4shell`), so an image with an unfixed JDK or JRE cannot be built on any platform.
- Because a fixed build is now guaranteed at build time, the interim `-XX:-UseBASE64Intrinsics` flag is dropped from the backend image, its entrypoint and the compose `migrate` service. Invariant 42 is met by the fixed build, and the Base64 intrinsic runs again. Decision 4 (the flag for a third-party image with no fix) is unchanged.
- CodeQL still analyzes Java up to 26 only, so `-PcodeqlJavaRelease=26` stays until it supports Java 27.
