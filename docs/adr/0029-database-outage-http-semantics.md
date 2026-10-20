# ADR 0029: Database Outage HTTP Semantics

## Status

Accepted. Issue: [#246](https://github.com/stevdrey/dokene/issues/246), found by the resilience suite [#123](https://github.com/stevdrey/dokene/issues/123) (RES-09). Extends the generic empty-`503` policy of [ADR 0006](0006-durable-append-only-audit.md) and the boundary logging rules of [ADR 0019](0019-ai-failure-handling-telemetry-and-audit.md) §6 to the runtime database.

## Context

With PostgreSQL stopped, data endpoints waited for Hikari's default 30 s `connectionTimeout` and then answered Spring's generic JSON `500`. Nothing leaked, and recovery needed no restart. But clients saw a half-minute spinner. A database outage also looked the same as an application defect, both to the SPA and to monitoring.

A database outage is an expected operational state, not a bug. A request can first need a connection in two places:

- inside the security filter chain (tenant membership resolution in `TenantContextRequestFilter`, or OIDC login), where no `@ControllerAdvice` runs;
- inside MVC (application services and repositories).

## Decision

1. **Bounded wait.** The runtime pool waits at most `spring.datasource.hikari.connection-timeout` for a connection. It is set from `DOKENE_DB_CONNECTION_TIMEOUT_MS`, default `3000` ms (Hikari's minimum is `250`). Flyway's migration connection is not affected.
2. **One answer for an outage.** A failure is a database outage when its cause chain contains any of these:
   - `SQLTransientConnectionException` (the pool timeout);
   - Spring's `CannotGetJdbcConnectionException`;
   - Hibernate's `JDBCConnectionException`;
   - any `SQLException` with SQLState class `08` (connection exception).

   `DatabaseUnavailability` (package `database`, module-neutral and enforced by ArchUnit) makes that decision. Such a failure answers an **empty `503 Service Unavailable` with `Retry-After`**. The value comes from `dokene.datasource.unavailable-retry-after` (`DOKENE_DB_UNAVAILABLE_RETRY_AFTER`, default `5s`), in whole seconds rounded up.
3. **Both boundaries.**
   - `DatabaseUnavailableExceptionHandler` is an unscoped advice that runs before the module-scoped advices, and it maps MVC failures.
   - `AuditRequestFilter` maps failures thrown from filters, the same way it already maps audit failures. It also keeps the server-generated `X-Request-Id`.

   A `CannotCreateTransactionException` with no connection-level cause is a defect. It stays an empty `500`.
4. **Class-only logging.** The outage is logged at `WARN` with the class of the innermost connection-level failure and the MDC correlation id. The exception message and cause are never logged, because pool and driver messages can name hosts, users and statements.
5. **Unchanged.**
   - An audit write that fails because of the outage is still the audit `503` of ADR 0006, without `Retry-After`.
   - Other database errors keep their module mapping (for example a conflict becomes `409`), or remain `500`.
   - Recovery stays automatic. The pool reconnects once PostgreSQL is reachable, and the session survives.

## Consequences

- During an outage the SPA gets a fast `503` and can show "service unavailable, try again" honoring `Retry-After`. Monitoring can count outage `503`s separately from `500` defects.
- Under heavy load, pool exhaustion now also answers `503` after the bounded wait instead of queueing for 30 s. That is intended: the caller is told to retry instead of holding a request thread. Size the pool and the timeout together.
- A request that has already started writing its response cannot be converted. It is left as the container finishes it, the same rule as for audit failures.
