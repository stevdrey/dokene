# Issue #322 OpenAI endpoint validation: verification record

Status: single PR. It closes audit finding L11 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): `OpenAiConfiguration` passed any `dokene.ai.openai.base-url` to the SDK, plain `http://` and arbitrary hosts included, so a typo or a tampered configuration could send the API key and customer data in cleartext to any host. The policy is described in [ADR 0016](../adr/0016-openai-responses-api-adapter-with-structured-outputs.md) section 6, the [AI and Automation](../wiki/AI-and-Automation.md) provider section, `.env.example` and security invariant 29.

This record is sanitized: it gives rules, outcomes and test names only. It contains no prompt, customer text, model output or secret. Every endpoint below is synthetic or loopback.

## Environment

- **Run:** 2026-11-07, branch `322-securityl11-validate-the-openai-provider-endpoint`, based on `main` at `f0b5062`. The code was rechecked on that base before the change: `OpenAiConfiguration.openAiClient` called `builder.baseUrl(properties.baseUrl())` for any non-blank value and `OpenAiProviderProperties` did not validate it, so L11 still applied as reported. The SDK client also followed redirects by default (`followRedirects` is `true` unless set). **Tested revision:** the working tree of the commit that adds this record, on top of `f0b5062`; a record cannot name its own commit hash.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build (Temurin 26.0.2); `com.openai:openai-java` 4.99.0.
- **Data:** synthetic only. Redirect behaviour is exercised against in-process loopback `HttpServer` stubs; no live OpenAI call.

## Policy

| Endpoint | Accepted |
| --- | --- |
| blank or unset | yes, as `https://api.openai.com/v1` |
| `https` on `api.openai.com` (any case, any valid port, any path) | yes |
| `http` or `https` on exactly `localhost`, `127.0.0.1` or `[::1]` | yes, for local test servers such as the fault-injection stub of suites #121 and #122 |
| `https` on a host listed in `dokene.ai.openai.additional-trusted-hosts` | yes; the list is empty by default and entries must be exact lower-case host names |
| anything else | no: startup fails with an `IllegalArgumentException` that does not echo the value |

Always rejected: user info, query or fragment (empty `?` and `#` included), ports `0` or above `65535`, relative, opaque, host-less and unparsable values, registry-based authorities (percent-encoded, non-ASCII or underscore hosts), `*.localhost`, other `127.0.0.0/8` addresses and numeric or IPv4-mapped loopback forms, and `http` towards a non-loopback host, listed hosts included. A listed host does not widen to its sub-domains or parents.

The client receives `scheme://host[:port]path` rebuilt from the validated components, lower-cased scheme and host, and `followRedirects(false)`. Validation runs in the `OpenAiProviderProperties` constructor, so a rejected value fails property binding and no `OpenAIClient` is ever built.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| Blank and unset give the OpenAI default; approved HTTPS and exact loopback endpoints are accepted and canonicalized | `OpenAiEndpointPolicyTest.anUnsetBaseUrlIsTheOpenAiEndpoint`, `acceptsOpenAiOverHttpsAndExactLoopbackTestServers` (8 cases) |
| Bypass cases fail closed: cleartext and other schemes, look-alike, parent, sibling and sub-domain hosts, trailing dot, user-info tricks (`api.openai.com@evil`, `:443@evil`, credentials), query and fragment (empty included, `#@` trick), backslash, percent-encoded, underscore and non-ASCII hosts, embedded whitespace and control characters, loopback look-alikes (`localhost.evil`, `*.localhost`, `localhost.`, `127.1`, hex and decimal `127.0.0.1`, `127.0.0.2`, `0.0.0.0`, IPv4-mapped `::1`), private and metadata addresses, relative, opaque, host-less and malformed values, bad ports | `OpenAiEndpointPolicyTest.rejectsEveryOtherEndpointBeforeAnyClientIsBuilt` (50 cases) |
| The error never echoes the configured value | `OpenAiEndpointPolicyTest.theRejectionNeverEchoesTheConfiguredValue` |
| A gateway is accepted only when listed and over HTTPS; no sub-domain or parent widening; credentials and query stay forbidden | `OpenAiEndpointPolicyTest.aTrustedGatewayIsAcceptedOnlyWhenExplicitlyListedAndOverHttps` |
| Malformed trusted-host entries are rejected | `OpenAiEndpointPolicyTest.rejectsMalformedTrustedHostEntries` (11 cases), `rejectsANullTrustedHostEntry` |
| An unsafe endpoint fails application startup and no `OpenAIClient` bean exists | `OpenAiConfigurationTest.failsStartupBeforeAnyClientIsBuiltWhenTheBaseUrlIsUnsafe` (7 values, an unlisted gateway included) |
| A listed gateway binds from comma-separated configuration and is canonicalized | `OpenAiConfigurationTest.bindsTheCanonicalEndpointAndExplicitlyTrustedHosts` |
| The shipped `application.yml`: unset or empty variables give the default endpoint and no trusted hosts; the loopback stub and a listed gateway work; an unsafe value fails | `OpenAiConfigurationTest.theShippedApplicationYamlDefaultsToTheOpenAiEndpointWithNoTrustedHosts` |
| The configured client never follows a `307` to another host (the target stub receives nothing), while the SDK default would (control) | `OpenAiConfigurationTest.theConfiguredClientNeverFollowsARedirectToAnotherHost` |
| Existing adapter behaviour against the loopback stub is unchanged | `OpenAiResponsesApiAdapterTest` (45 tests), `OpenAiCallCancellationSpikeTest` (opt-in) |

## Results

| Command | Kind | Result |
| --- | --- | --- |
| `./gradlew test --tests 'io.github.stevdrey.dokene.ai.provider.openai.*'` | automated | `OpenAiEndpointPolicyTest` 74, `OpenAiConfigurationTest` 15, `OpenAiResponsesApiAdapterTest` 45 tests, 0 failures |
| `./gradlew check spikeTest --tests '*OpenAiCallCancellationSpikeTest'` | automated | passed: 2516 tests and 10 spike tests, 0 failures, 0 skipped |

## Not verified here

No live OpenAI call and no Docker Compose run were made. A deployment that today sets `DOKENE_AI_OPENAI_BASE_URL` to a non-loopback gateway must add that host to `DOKENE_AI_OPENAI_ADDITIONAL_TRUSTED_HOSTS` (and serve it over HTTPS) or the backend will not start; that is the intended fail-closed behaviour. DNS resolution is not pinned: a listed or OpenAI host name is trusted as resolved by the platform resolver, which is outside this finding.
