# ADR 0034: Java 27 Runtime Baseline and the Java4Shell Response

## Status

Accepted. Issue: [#429](https://github.com/stevdrey/dokene/issues/429), part of epic [#305](https://github.com/stevdrey/dokene/issues/305).

It replaces the Java 26 baseline named in [ADR 0015](0015-structured-next-best-action-recommendation-contracts.md) and [ADR 0022](0022-containerized-local-environment.md); those ADRs are otherwise unchanged.

## Context

Java4Shell (CVE-2026-114938), disclosed on 2026-11-17, is memory corruption in the HotSpot Base64 decoder intrinsic, reachable through `java.util.Base64.Decoder` on x86-64 hosts with AVX-512 VBMI. Vendors shipped fixes for 17, 21, 25 and 27. Java 26, the project's baseline, gets none: it no longer receives updates. The backend decodes client-supplied cursors with `Base64.getUrlDecoder()`, so the input is reachable. Details: [java4shell-cve-2026-114938.md](../security/java4shell-cve-2026-114938.md).

Two supported targets had a fixed build: Java 27 (27.0.1.1) and Java 25 LTS (25.0.5.1). On 2026-11-20 the official Temurin container images of both lines still held the unfixed build; only the binaries used by `setup-java` were fixed.

## Decision

1. **Java 27 is the baseline.** The Gradle toolchain, CI and the backend image move from 26 to 27. Java 27 needs no source change (the full test suite passes) and keeps every language feature the code uses. Moving to 25 LTS would have removed features the code may already use and is a larger, separate decision.
2. **CI requires a fixed JDK.** Each `setup-java` step uses `check-latest: true`, so a cached older build on the runner is not reused, and the job then runs `scripts/check-java4shell-runtime.sh --require-patched`, which fails unless the JDK is a vendor-fixed build.
3. **A runtime image without a fixed build runs mitigated.** Every JVM started from the backend image starts with `-XX:-UseBASE64Intrinsics`: on the java command line of `entrypoint.sh` and of the compose `migrate` service (an override of `JAVA_TOOL_OPTIONS` cannot remove it) and in `JAVA_TOOL_OPTIONS` (which covers other java invocations, including the Gradle build stage). The flag stays until the digests point at an image with a fixed build.
4. **Third-party Java images take the vendor fix.** Keycloak moves to `26.8.2-3`, which ships Red Hat OpenJDK 21.0.13.1. When a vendor image has no fix yet, the same flag goes into its JVM options (`JAVA_OPTS_APPEND` for Keycloak).
5. **One script answers "is this JVM exposed".** `scripts/check-java4shell-runtime.sh` reads the version, vendor, architecture and the effective `UseBASE64Intrinsics` value from the JVM itself and prints `PATCHED`, `NOT_AFFECTED`, `MITIGATED` or `VULNERABLE`. CI, the image verification and operators use it.

## Consequences

- The backend image ships a known-unfixed JDK with the vulnerable intrinsic off. That is an interim state, tracked in the security page; the fix is a digest bump once the Temurin 27 images are rebuilt.
- Base64 decoding in the mitigated JVM runs in plain Java code: slower, but the backend decodes only short cursors.
- Java 27 is not LTS. Its support ends when Java 28 ships; the baseline after that is a new decision.
- A version table of fixed builds lives in the script. A future advisory for the same bug means editing that table.
