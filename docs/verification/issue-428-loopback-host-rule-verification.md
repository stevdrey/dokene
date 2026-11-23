# Issue #428 loopback host rule: verification record

Status: single PR. Before it, the backend decided "is this host loopback?" in four places with four copies of the rule: `PublicOrigin` and `OpenAiEndpointPolicy` each kept the set `localhost`, `127.0.0.1`, `[::1]`; `GraphApiProperties` also accepted any `*.localhost`; `RedirectOriginStartupValidator` (PR #406, issue #318) had the wide rule for the back-channel replay port check. The rule now lives in `io.github.stevdrey.dokene.http.LoopbackHosts`, and each caller picks one of its two named rules on purpose. Security invariant 29 states the rule each provider policy uses; audit finding I3 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md) is closed by the decision below.

This record is sanitized: it gives rules, outcomes and test names only. Every host below is synthetic or loopback.

## Environment

- **Run:** 2026-11-23, branch `428-backendsecurity-keep-the-loopback-host-rule-in-one-place`, based on `main` at `27389f1`.
- **Toolchain:** Gradle Wrapper with the Java 27 toolchain declared by the build (OpenJDK 27.0.1.1).
- **Data:** none. The change is configuration validation only; no provider is called.

## Rules and callers

| Rule | Accepts | Used by | Why |
| --- | --- | --- | --- |
| `LoopbackHosts.isExactSpelling` | exactly `localhost`, `127.0.0.1`, `[::1]`, compared as written | `PublicOrigin` (plain `http` origin) | the origin is used as written and compared with what the identity provider registered, so only the spelling a browser writes |
| same | same | `OpenAiEndpointPolicy`, `GraphApiProperties` (loopback test server) | allow-lists for credentials and customer data (invariant 29) |
| `LoopbackHosts.reachesLoopback` | `localhost` and any name under it, with or without the root dot, any case; every literal address in `127.0.0.0/8` or equal to `::1` however written; no DNS lookup | `RedirectOriginStartupValidator.requireReplayUriReachesTheListener` | it only decides whether the replay port must equal `server.port`, so a wider rule refuses more, never less |

## Decision on `*.localhost`

The Graph policy no longer accepts `*.localhost` (for example `http://stub.localhost/v23.0`); the OpenAI policy keeps refusing it. Whether such a name reaches loopback depends on the resolver honouring RFC 6761 (audit I3), and the Graph base URL receives tenant bearer tokens. No configuration, script, compose file or document in the repository used a `*.localhost` Graph URL; the only use was the test case that pinned it as accepted, which now pins it as refused. This narrows the loopback allowance only; which provider hosts are trusted is unchanged.

## Automated coverage

- `LoopbackHostsTest`: both rules on the three exact spellings, on other spellings (`LOCALHOST`, `localhost.`, `stub.localhost`, `127.0.0.2`, `127.1`, `::1`, `[0:0:0:0:0:0:0:1]`, `[::ffff:127.0.0.1]`), and on hosts that are not loopback (`null`, empty, `.`, `localhost.evil.example`, `evillocalhost`, `128.0.0.1`, `0.0.0.0`, `[::2]`, an unclosed bracket, an underscore name). `noOtherMainClassDeclaresItsOwnLoopbackRule` scans `backend/src/main/java` and fails when any class other than `LoopbackHosts` holds a loopback literal, `LOOPBACK_HOSTS`, an `isLoopback(` predicate or `isLoopbackAddress`; run against the base revision's sources, the same pattern matches exactly the four former copies.
- `GraphApiPropertiesTest.rejectsEveryOtherHostSoTenantCredentialsCannotLeak`: now also refuses `http://stub.localhost/v23.0`, `http://localhost./v23.0`, `http://127.0.0.2` and `http://[0:0:0:0:0:0:0:1]`. That is the only accepted or refused host changed in the existing suites.
- `PublicOriginTest`, `OpenAiEndpointPolicyTest`, `RedirectOriginStartupTest` and `ModuleDependencyArchitectureTest` pass unchanged (the `http` package stays neutral: `LoopbackHosts` depends on no other Dokene module).

## Results

- `./gradlew test --tests '*LoopbackHostsTest' --tests '*GraphApiPropertiesTest' --tests '*OpenAiEndpointPolicyTest' --tests '*PublicOriginTest' --tests '*RedirectOriginStartupTest' --tests '*ModuleDependencyArchitectureTest'` (from `backend/`): passed, 373 cases (37, 46, 82, 108, 76, 24), 0 failed.
- `./gradlew check` (from `backend/`): passed, 3222 tests, 0 failed, 0 skipped. Only this record was added after that run.

## Not verified here

- No live stack run: the change moves validation logic and narrows one test-only allowance; the local stack uses neither a `*.localhost` Graph URL nor a non-default replay URI.
- A name that only a hosts file maps to loopback is recognized by neither rule, as before.
