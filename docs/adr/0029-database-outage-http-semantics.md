# ADR 0029: Database Outage HTTP Semantics

## Status

Accepted. Issue: [#246](https://github.com/stevdrey/dokene/issues/246), found by the resilience suite [#123](https://github.com/stevdrey/dokene/issues/123) (RES-09). Extends the generic empty-`503` policy of [ADR 0006](0006-durable-append-only-audit.md) and the boundary logging rules of [ADR 0019](0019-ai-failure-handling-telemetry-and-audit.md) §6 to the runtime database.

## Context

With PostgreSQL stopped, data endpoints waited for Hikari's default 30 s `connectionTimeout` and then answered Spring's generic JSON `500`. Nothing leaked, and recovery needed no restart. But clients saw a half-minute spinner. A database outage also looked the same as an application defect, both to the SPA and to monitoring.

A database outage is an expected operational state, not a bug. A request can first need a connection in two places:

- inside the security filter chain (tenant membership resolution in `TenantContextRequestFilter`, or OIDC login), where no `@ControllerAdvice` runs;
- inside MVC (application services and repositories).

## Decision

1. **Bounded wait.** The runtime pool waits at most `spring.datasource.hikari.connection-timeout` for a connection. It is set from `DOKENE_DB_CONNECTION_TIMEOUT_MS`, default `3000` ms. Flyway's migration connection is not affected. `TenantPersistenceConfiguration` applies and checks every bound below at startup.
   - Idle pooled connections are checked for liveness within `validation-timeout` (`1000` ms) before they are handed out. Hikari does not keep that below `connection-timeout`, and a borrower that meets a stale connection after a blackholed outage spends it before trying another. So it is capped at half the pool wait, never below Hikari's 250 ms floor, and startup refuses a pool wait below `500` ms, which would leave no room for that check.
   - A statement already in flight when the network path goes silent (for example a blackholed route, where no TCP reset arrives) is not covered by the pool wait. It is bounded by the pgJDBC `socketTimeout`, from `dokene.datasource.socket-timeout` (`DOKENE_DB_SOCKET_TIMEOUT`, default `30s`); pgJDBC disables it by default. On expiry the driver fails with SQLState `08006`, which maps to the outage answer below.
   - So that only a silent network reaches that ceiling, the runtime pool also sets PostgreSQL's `statement_timeout` (pgJDBC `options`), from `dokene.datasource.statement-timeout` (`DOKENE_DB_STATEMENT_TIMEOUT`, default `25s`). Startup refuses it unless it is shorter than the socket timeout. A statement or lock wait (`FOR UPDATE`, advisory locks) that is merely long on a healthy server is then cancelled by the server as `57014` (`query_canceled`). That is not an outage, and the connection survives, instead of becoming a lost connection reported as one.
2. **One answer for an outage.** A failure is a database outage when its cause chain contains any of these:
   - `SQLTransientConnectionException` (the pool timeout);
   - Spring's `CannotGetJdbcConnectionException`;
   - Hibernate's `JDBCConnectionException`;
   - any `SQLException` with SQLState class `08` (connection exception);
   - any `SQLException` with the PostgreSQL states that end a running session because the server is stopping, has crashed, or is still starting or recovering: `57P01` (`admin_shutdown`), `57P02` (`crash_shutdown`) and `57P03` (`cannot_connect_now`). The rest of class `57`, notably `57014` (`query_canceled`), is not an outage.

   `DatabaseUnavailability` (package `database`, module-neutral and enforced by ArchUnit) makes that decision. Such a failure answers an **empty `503 Service Unavailable` with `Retry-After`**. The value comes from `dokene.datasource.unavailable-retry-after` (`DOKENE_DB_UNAVAILABLE_RETRY_AFTER`, default `5s`), in whole seconds rounded up.
3. **One boundary: `AuditRequestFilter`.** It runs outside Spring Security and sees both kinds of failure:
   - failures thrown from filters, such as tenant membership resolution, where no `@ControllerAdvice` runs;
   - MVC failures that no advice resolves. These leave the `DispatcherServlet` before the response is committed. No module advice handles `Exception`, `RuntimeException` or `DataAccessException`, so a database failure always reaches the filter.

   The filter checks the whole cause chain, so no list of exception types has to anticipate how Hibernate, JPA or `JdbcTemplate` wraps a lost connection. Mapping outages in a `HIGHEST_PRECEDENCE` advice instead was rejected for two reasons. First, Spring's cause matching would let it take over module exceptions that wrap a database type. Second, its type list kept missing translations, for example `DataAccessResourceFailureException` for SQLState `08`, or the `57P0x` states.

   A database failure that is not an outage, such as a transaction that cannot start for another reason or a full disk (`53100`), keeps Spring's default handling, a `500` with the full root cause in the server log.

   The filter's response reset keeps three kinds of headers:
     - the server-generated `X-Request-Id`;
     - the `Access-Control-*` and `Vary` headers that Spring Security's CORS filter already validated, so a cross-origin SPA can read the `503` instead of seeing a network error;
     - the security headers Spring Security's header writer already added (CSP, Permissions-Policy, `X-Content-Type-Options`, `X-Frame-Options`, Referrer-Policy and cache control), which every BFF response carries (ADR 0023, security invariant 22).

     It drops every other header. The audit `503` keeps the same set. `Retry-After` is listed in the CORS `Access-Control-Expose-Headers`, because it is not CORS-safelisted and the SPA must be able to read it.

4. **Class-only logging.** The outage is logged at `WARN` with the class of the innermost connection-level failure and the MDC correlation id. The exception message and cause are never logged, because pool and driver messages can name hosts, users and statements.
5. **Audit writes.**
   - An audit write that fails because of the outage is still the empty audit `503` of ADR 0006, and now also carries `Retry-After`.
   - `TransactionalAuditRecorder` keeps dropping the cause, but it records whether the database was unreachable on `AuditPersistenceException`. That flag is a boolean, not text. `AuditRequestFilter` and `AuditExceptionHandler` add `Retry-After` when it is set.
   - Any other audit failure keeps the plain `503`.
6. **Unchanged.**
   - Other database errors keep their module mapping (for example a conflict becomes `409`), or remain `500`.
   - Recovery stays automatic. The pool reconnects once PostgreSQL is reachable, and the session survives.

## Consequences

- During an outage the SPA gets a fast `503` and can show "service unavailable, try again" honoring `Retry-After`. Monitoring can count outage `503`s separately from `500` defects.
- Under heavy load, pool exhaustion now also answers `503` after the bounded wait instead of queueing for 30 s. That is intended: the caller is told to retry instead of holding a request thread. Size the pool and the timeout together.
- A request that has already started writing its response cannot be converted. It is left as the container finishes it, the same rule as for audit failures.
