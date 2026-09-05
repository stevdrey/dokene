# ADR 0006: Durable Append-Only Audit

## Status
Accepted

## Decision
The `audit` module stores immutable security and business events in PostgreSQL. Its
application-facing `AuditRecorder` and `AuditReader` interfaces do not expose JDBC,
JPA entities, arbitrary payloads, or caller-supplied actor/tenant attribution.

Each envelope contains a server-generated UUID and UTC timestamp (microsecond
precision), event type, outcome, correlation UUID, optional target type/UUID, and
trusted tenant/actor/membership references. Historical identifiers have no cascading
foreign keys: deleting or changing an operational entity must not rewrite history.
The runtime role can append and select authorized tenant rows, but cannot update,
delete, truncate, alter the table, or bypass RLS. Migration/database administrators
remain trusted; this is not cryptographic tamper evidence against administrators.

## Attribution and correlation
The recorder obtains tenant, actor, and membership from `TenantContextProvider` at
the recording boundary. `AuthorizationDeniedEvent` attribution and foreign resource
tenant IDs are never copied into stored metadata. Denial event timestamps are also
created by the recorder. Explicit-context `evaluate` methods remain side-effect-free;
`require*` and boolean `has*` authorization enforcement paths dispatch durable denials.

`AuditRequestFilter` wraps the servlet/security chain in a fresh server-generated
correlation UUID, ignoring inbound correlation headers. `AuditExecutionContext`
provides bounded `ScopedValue` scopes for internal work. Jobs must explicitly establish
both correlation and trusted tenant scopes; ordinary executor tasks do not inherit
them. Request-created asynchronous work must pass these values to its own entry scope.
Missing correlation is a programming error and prevents recording/the state change.

A missing tenant context produces a global `AUTHORIZATION_DENIED` event with
`NO_TENANT_CONTEXT` and no tenant, actor, or membership attribution. We do not infer an
actor from unverified input. RLS permits this narrow INSERT exception only when the
connection has no verified tenant capability. Global rows are invisible to runtime
SELECT even without a tenant context. Investigation of those rows requires a trusted
administrative database workflow outside this application API. They are not proof of
an authenticated identity, and unauthenticated SQL clients holding runtime credentials
could append such unattributed rows; this does not grant tenant read/write access.

## Metadata privacy
Metadata is a sealed set of typed records persisted as constrained scalar columns:

- Authorization denial: optional enumerated permission and enumerated denial reason.
- Membership role change: previous and new role, excluding `OWNER` and no-op changes.

The listener translates only known reasons; unknown text becomes `UNSPECIFIED`.
There is no general string/map/JSON payload API. SQL checks reject unknown permission,
reason, role, and event shapes. Secrets, tokens, credentials, message bodies, request
payloads, exception messages, names, IP addresses, and foreign-tenant identifiers are
not metadata fields. Adding a new event requires deliberate code/schema changes and
privacy review. UUID references remain sensitive operational identifiers and require
access control. `FAILURE` is reserved in the outcome vocabulary; neither current event
type permits it and no general failure-payload entry point exists.

## Transactions and failure policy
Successful security-sensitive database transitions and their audit INSERT share the
business transaction. The recorder uses `MANDATORY` propagation for successful role
changes; calling it outside a transaction fails. There is no after-commit best-effort
listener. A failed INSERT rolls back the membership update and audit event together;
an optimistic locking conflict produces no successful event.

Authorization denials use synchronous `REQUIRES_NEW` transactions and commit before
returning false or throwing access denied. They survive a rollback of the enclosing
business transaction. This uses an additional pooled connection while the outer
transaction is suspended; pool sizing must account for that demand. Denial transactions
have a ten-second transaction timeout; datasource connection-acquisition limits still
apply. No audit table foreign keys acquire locks on business rows, avoiding dependencies
on uncommitted membership changes. No retries or unbounded queues are introduced.

Audit INSERT failures and independent audit commit failures propagate as
`AuditPersistenceException`. Business transaction commit failures abort the whole
operation through the existing transaction manager error contract. The recorder
emits a fixed error log with only the correlation UUID, discarding underlying SQL error
text and exception causes because drivers can include row values. Operators must monitor
this error signal. HTTP boundaries return generic empty 503 responses; a denied action
never becomes allowed because its audit write failed. A response that is already committed
cannot be replaced, so auditing must precede response/side-effect commitment.

For future external side effects, a database rollback cannot undo an already-sent
message. Such use cases must durably record authorization/intent and an outbox command
before delivery, then append outcomes through a separately durable workflow. An outbox,
audit export, retention/purge jobs, and cryptographic history sealing are outside this ADR's
implementation scope.

## Internal APIs and first integration
`MembershipRoleService.changeRole(targetIdentity, newRole)` loads the target membership
inside the active tenant, requires `MEMBERSHIP_ROLE_UPDATE`, checks resource ownership,
applies existing domain validation, and saves with optimistic concurrency control. Only
transitions among `ADMIN`, `OPERATOR`, and `VIEWER` are supported. Both source and target
`OWNER` roles are rejected; ownership transfer and last-owner rules need a separate use case.
Validation/not-found/conflict errors are not successful transitions and do not create
success events. Repository adapters remain persistence primitives, not authorized use cases;
future production membership mutation entry points must use an authorized, audited service.

`AuditReader.read(before, limit)` requires `AUDIT_READ`, takes the tenant only from the
active context, and combines application filtering with signed-capability RLS. The
cursor is an exclusive `(timestamp, UUID)` boundary in descending PostgreSQL order.
Default size is 50, maximum 100; invalid sizes are rejected. Pages are not a snapshot
across concurrent inserts. Global rows cannot be retrieved through this API. No new HTTP
read endpoint, user interface, or audit-write endpoint is introduced.

## Verification
PostgreSQL/Testcontainers tests exercise real migration/runtime roles, RLS and forged
capabilities, append-only permissions, global invisibility, privacy constraints,
authorized pagination, atomic success/rollback, independently committed denials,
audit outages, and concurrent optimistic conflicts. Unit and servlet tests cover
envelope validation, metadata translation, correlation cleanup and executor boundaries,
and generic HTTP failure responses.
