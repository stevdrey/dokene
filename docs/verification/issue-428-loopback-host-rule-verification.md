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
| `LoopbackHosts.reachesLoopback` | `localhost` and any name under it, with or without the root dot, any case; every address literal `InetAddress.ofLiteral` reads as loopback (IPv4 dotted or shortened decimal such as `127.1`, any IPv6 form); the unspecified address (`0.0.0.0`, `[::]`), because on Linux a connection to it reaches the local host; octal or hex spellings are not recognized, and the JDK never reaches loopback through them (it reads `0177.0.0.1` as decimal 177.0.0.1 and refuses `0x7f.0.0.1`); no DNS lookup | `RedirectOriginStartupValidator.requireReplayUriReachesTheListener` | it only decides whether the replay port must equal `server.port`, so a wider rule refuses more, never less |

## Decision on `*.localhost`

The Graph policy no longer accepts `*.localhost` (for example `http://stub.localhost/v23.0`); the OpenAI policy keeps refusing it. Whether such a name reaches loopback depends on the resolver honouring RFC 6761 (audit I3), and the Graph base URL receives tenant bearer tokens. No configuration, script, compose file or document in the repository used a `*.localhost` Graph URL; the only use was the test case that pinned it as accepted, which now pins it as refused. This narrows the loopback allowance only; which provider hosts are trusted is unchanged.

## Review follow-ups

A code review of PR #439 led to these changes in the commit after the first one:
- Refusal messages of `PublicOrigin`, `OpenAiEndpointPolicy` and `GraphApiProperties` take the host list from `LoopbackHosts.EXACT_SPELLINGS_TEXT`. The Graph refusal now names the three exact hosts, so an operator whose `*.localhost` URL is refused is told what is accepted. The OpenAI and `PublicOrigin` texts are unchanged.
- The guard test matches a rule (a host set, a loopback predicate, a comparison with a loopback spelling) instead of any loopback literal. It finds the sources from `backend/` or from the repository root, and fails when it cannot find them. Against the base revision it still flags exactly the four former copies.
- The Javadoc of the wide rule states what it does not recognize (octal and hex IPv4 spellings), and `LoopbackHostsTest` pins it.
- Not done here: the Graph and OpenAI endpoint policies still differ outside the loopback rule (scheme case, port bounds, normalization). Unifying them is issue #440 (PR #441, stacked on this one).

A second review led to these changes in the next commit:
- The wide rule also counts the unspecified address (`0.0.0.0`, `[::]`), so a replay URI on it must use `server.port` like any other loopback spelling. The rule only narrows, so this refuses more, never less.
- `EXACT_SPELLINGS_TEXT` is derived from the ordered list of exact spellings, so the refusal text cannot drift from the rule; the text is unchanged.
- The guard also matches Yoda comparisons, `equalsIgnoreCase`, `List.of`, a loopback spelling anywhere in a `Set.of`/`List.of`, the first `case` label of a loopback spelling and `isAnyLocalAddress`. It remains a text scan: forms such as `Objects.equals(h, "localhost")` or a later label in a multi-label `case` are not matched. ADR 0007 states the unspecified address in its replay-port rule.
- `LoopbackHostsTest` pins the wide rule on `127.1`, `2130706433` and `[::ffff:127.0.0.1]`, as the Javadoc states.
- Formatting only: one wrapped line in `PublicOrigin`, import order in `GraphApiProperties`.

## Automated coverage

- `LoopbackHostsTest`: both rules on the three exact spellings, on other spellings that reach loopback (`LOCALHOST`, `localhost.`, `stub.localhost`, `127.0.0.2`, `127.1`, `2130706433`, `::1`, `[0:0:0:0:0:0:0:1]`, `[::ffff:127.0.0.1]`, `0.0.0.0`, `[::]`), which the exact rule refuses and the wide rule accepts, and on hosts that are not loopback (`null`, empty, `.`, `localhost.evil.example`, `evillocalhost`, `128.0.0.1`, `[::2]`, an unclosed bracket, an underscore name, `0177.0.0.1`, `0x7f.0.0.1`). `theRefusalTextNamesEveryExactSpelling` pins the refusal text. `noOtherMainClassDeclaresItsOwnLoopbackRule` scans `backend/src/main/java` and fails when any class other than `LoopbackHosts` holds a loopback rule: `isLoopbackAddress`, `LOOPBACK_HOSTS`, a `boolean isLoopback…(` declaration, `isAnyLocalAddress`, a `Set.of(`/`List.of(` holding a loopback spelling, an `equals`/`equalsIgnoreCase`/`endsWith`/`startsWith`/`contains` comparison with one in either direction, or a `case` whose first label is one; a bare literal such as a default host name does not trip it; run against the base revision's sources, the same pattern matches exactly the four former copies.
- `GraphApiPropertiesTest.rejectsEveryOtherHostSoTenantCredentialsCannotLeak`: now also refuses `http://stub.localhost/v23.0`, `http://localhost./v23.0`, `http://127.0.0.2` and `http://[0:0:0:0:0:0:0:1]`. That is the only accepted or refused host changed in the existing suites.
- `PublicOriginTest`, `OpenAiEndpointPolicyTest`, `RedirectOriginStartupTest` and `ModuleDependencyArchitectureTest` pass unchanged (the `http` package stays neutral: `LoopbackHosts` depends on no other Dokene module).

## Results

- `./gradlew test --tests '*LoopbackHostsTest' --tests '*GraphApiPropertiesTest' --tests '*OpenAiEndpointPolicyTest' --tests '*PublicOriginTest' --tests '*RedirectOriginStartupTest' --tests '*ModuleDependencyArchitectureTest'` (from `backend/`): passed, 385 cases (49, 46, 82, 108, 76, 24), 0 failed, after the second review's changes (375 after the first review's).
- `./gradlew check` (from `backend/`): passed, 3234 tests, 0 failed, 0 skipped, after the second review's changes (3224 after the first review's, 3222 on the first commit). Only this record was edited after that run.

## Not verified here

- No live stack run: the change moves validation logic and narrows one test-only allowance; the local stack uses neither a `*.localhost` Graph URL nor a non-default replay URI.
- A name that only a hosts file maps to loopback is recognized by neither rule, as before.
