# Issue #320 Forwarded headers at the trusted edge: verification record

Status: single PR, part of epic [#305](https://github.com/stevdrey/dokene/issues/305). It closes [#320](https://github.com/stevdrey/dokene/issues/320) (audit finding L8): the nginx edge passed every `Forwarded` and `X-Forwarded-*` header a client sent on to the backend. The edge now decides every forwarding header the backend sees. The contract is in [ADR 0007](../adr/0007-oidc-server-side-session.md) and [security invariant 36](../security/security-invariants.md).

This record is sanitized: it gives outcomes and settings only. It contains no credential, cookie, token, authorization code or state value, and no request recipe for the direct backend port. Every forged value was a documentation address (`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`) or the name `evil.example`.

## Environment

- **Run:** 2026-11-14, branch `fix/320-normalize-forwarded-headers`, based on `main` at `d036bbc`.
- **Tested revision:** commit `c02c213` on top of `d036bbc`; every local and live check below ran on that tree. The second commit, `dab984c`, adds only a ShellCheck directive comment to `scripts/tests/nginx-headers-smoke.sh` (blob `458017bec30fae1b03fd1be7607c5c442233a3e9` there); CI ran on it. Git blob ids of the files under test: `infra/docker/frontend/proxy-upstream.conf` `a9398f50e46d4b9a69dcb81e2779fad17fc12cd4`, `infra/docker/frontend/trusted-proxies.conf` `1408bae65f172130ee03709626d26a778f6f59a4`, `infra/docker/frontend/nginx.conf` `4aefb9d39b465df32378022da2b2c46400c17d79`, `infra/docker/frontend/Dockerfile` `7a000e0255ec6f1050852e8624d8ccb90d924048`, `scripts/tests/nginx-headers-smoke.sh` `b5b077e5a6cad47d66216b7df343effdae8ddf3e`, `scripts/tests/nginx-login-rate-limit-smoke.sh` `c854e089b74712d1b4559b86bde8c133e971b198`.
- **Toolchain:** macOS 27.2.1, Docker Desktop (engine 29.7.2), Docker Compose 5.4.0, curl 8.7.1, nginx 1.31.7 (the pinned `nginx-unprivileged` image), Keycloak 26.8.2, Spring Boot 4.1.2.
- **CI:** the `nginx-headers` job runs both smoke tests on every pull request. On [PR #402](https://github.com/stevdrey/dokene/pull/402) at `dab984c`, every check passed: `nginx-headers` in run [43600787419](https://github.com/stevdrey/dokene/actions/runs/43600787419) (job 132464874416), `dev-env-smoke` with its ShellCheck gate in run [43600787358](https://github.com/stevdrey/dokene/actions/runs/43600787358) (job 132464874562). On `c02c213`, `nginx-headers` passed in run [43600422760](https://github.com/stevdrey/dokene/actions/runs/43600422760), and `dev-env-smoke` failed in run [43600422689](https://github.com/stevdrey/dokene/actions/runs/43600422689) on ShellCheck SC2016 for the two literal nginx variables in the echo stub, which `dab984c` fixes.
- **Not run:** `./gradlew check` and the frontend suite, because no backend or frontend source file changed.

## What changed

| Location | Before | After |
| --- | --- | --- |
| `/api`, `/oauth2`, `/login`, `/logout` and `/webhooks` locations of `nginx.conf` | each set only `Host localhost:8080` and `Connection ""`, so every client forwarding header reached the backend | each includes `snippets/proxy-upstream.conf` and sets no upstream header of its own |
| `proxy-upstream.conf` (new) | none | fixed `Host` and `Connection`; `X-Forwarded-For $remote_addr`; `X-Forwarded-Proto $scheme`; `X-Forwarded-Port 8080`; eleven other forwarding headers cleared |
| `trusted-proxies.conf` (new) and the `server` block | no `realip` | `realip` with `real_ip_header X-Forwarded-For` and `real_ip_recursive on`; the shipped list trusts no peer |

## A spec change found during the live run

The first live run under `framework` sent only `X-Forwarded-For` and `X-Forwarded-Proto`. Every check under `none` passed, but under `framework` the `redirect_uri` lost its `:8080`. When Spring's forwarded header handling sees `X-Forwarded-Proto`, it sets the scheme and clears the port, and it restores a port only from `X-Forwarded-Port` or from a port inside `X-Forwarded-Host`. Spec 0018 was updated on 2026-11-14: the edge now also sends a fixed `X-Forwarded-Port: 8080`, the same constant as the pinned `Host`, and never the client's value. The smoke test checks were written first and failed seven times on the earlier snippet, then passed with the change.

## Checks

| Check | Kind | AC | Outcome |
| --- | --- | --- | --- |
| `scripts/tests/nginx-headers-smoke.sh`: static check that `nginx.conf` holds no `proxy_set_header` line and that both `proxy_pass` locations include the snippet | automated | AC-1 | Pass |
| The same test: an untrusted peer sends forged values in every stripped header, in `X-Forwarded-For`, `-Proto`, `-Port` and `Host`, on `/api`, `/oauth2` and `POST /webhooks`, once plainly and once repeated in mixed letter case | automated | AC-1, AC-2 | No stripped header reaches the echo upstream; it gets exactly the peer address, `http`, `8080` and `localhost:8080` |
| The same test: a webhook `POST` through the edge, with forged headers, whose JSON body holds UTF-8 (`ñ`, `€`), escaped quotes, a backslash and a tab. The echo container's `/webhooks/body-check` route proxies to a second local server, so nginx reads the body, and logs `$request_body` unescaped | automated | AC-5, AC-6 | The upstream answers `200` and records the body byte for byte, so the bytes the backend's HMAC is computed over are unchanged |
| The same test: the shipped `trusted-proxies.conf` | automated | AC-4 | No `set_real_ip_from` line in the repository or the image; a client address sent from this peer is ignored |
| The same test: a mounted list that trusts this host's peer and `198.51.100.0/24` | automated | AC-3 | The client address is restored; the rightmost untrusted address wins over a forged one to its left; the proxy's `https` and forged port are not believed (`http`, `8080`); a malformed entry keeps the peer address; `203.0.113.7:4321` is restored as `203.0.113.7` (nginx 1.31.7 drops the port and does not refuse the entry); a fully trusted chain gives the leftmost address; no header gives the peer address |
| `scripts/tests/nginx-login-rate-limit-smoke.sh`, including the trusted peer case | automated | AC-3 | Pass; two restored client addresses get separate login start buckets, and the repeats of each get `429` |
| Live stack, backend under `none` (the default): login start through `http://localhost:5173` with and without forged `Forwarded`, `X-Forwarded-Host`, `-Port`, `-Proto: https`, `-Prefix`, `-Ssl` and `-For` | live | AC-6 | `redirect_uri` is `http://localhost:8080/login/oauth2/code/dokene` both times |
| The same: a full login of the realm test owner started at the edge with forged headers, then `GET /api/session` through the edge | live | AC-6 | Keycloak accepts the `redirect_uri` and sends the code to the registered callback; the session is authenticated |
| The same: provider logout (`POST /logout?provider=true`) through the edge with forged headers | live | AC-6 | `302`; the post logout redirect is `http://localhost:8080/` |
| The same: anonymous `GET /api/session` and an unsigned webhook `POST` through the edge | live | AC-6 | `401`; the webhook reaches the backend, which refuses it with `403` because the local stack has no Meta app secret configured (`app-secret-ref is unset` in the backend log), so no signature check runs on the body. The response carries the backend's `no-store` cache header, which nginx does not add |
| The same: `Strict-Transport-Security` on the session and logout responses through the edge with forged `X-Forwarded-Proto: https` | live | AC-6 | Not sent |
| All of the above again with `SERVER_FORWARD_HEADERS_STRATEGY=framework`, applied by recreating only the backend | live | AC-7 | Every check passes with the same values; no HSTS through the edge |
| Positive control under `framework`: the same forged request sent straight to the backend port, with the same `Host` as the edge sends | live | AC-7 | The `redirect_uri` changes and HSTS is sent, so the checks through the edge can fail. Under `none` the same control changes nothing. Without forged headers the direct port gives the registered `redirect_uri` |

The live checks ran from a script kept outside the repository, because it holds the direct port request; it loaded the test password from `.env` and handed it to curl on standard input. The backend was put back to `none` afterwards.

## Limitations

- The live flow was checked with curl, not a browser. The callback goes to the registered `http://localhost:8080` callback on the direct port, as it does in a browser, so the edge carries the login start, the session, the logout and the webhook, but not the callback itself.
- A signed webhook was not sent through the live edge, because the stack has no Meta app secret, and the backend refuses every webhook before it checks a signature. That the body passes unchanged is proven by the smoke test's raw body check against the echo upstream, not against the backend.
- The direct backend port still believes forwarding headers under `framework`. That port, the backend's own trusted peers and trusting a TLS tunnel's scheme are left to [#318](https://github.com/stevdrey/dokene/issues/318). Behind a TLS tunnel the backend sees `http` and sends no HSTS.
- The trusted proxy cases ran in the smoke test, not behind a real tunnel.
