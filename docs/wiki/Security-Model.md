# Security Model

Security is a first-class architectural property of Dokene.

The central security question is:

> **How do we prevent a tenant, user, integration, compromised credential, or AI-generated action from reading, modifying, or causing effects outside its authorized scope?**

## Critical assets

Dokene handles assets that require explicit protection:

- customer personally identifiable information;
- purchase history and business relationship data;
- tenant configuration and business rules;
- authentication/session state;
- AI provider credentials;
- messaging-provider credentials and tokens;
- OAuth or integration secrets;
- message content and delivery history;
- audit records;
- exports and backups.

## Primary threats

The initial threat model includes:

- cross-tenant data access;
- IDOR/BOLA-style object access;
- privilege escalation;
- credential or API-key leakage;
- prompt injection through imported or customer-controlled content;
- unsafe AI-triggered side effects;
- forged or replayed provider webhooks;
- duplicate outbound messages;
- SQL injection;
- SSRF through integrations;
- mass assignment;
- credential stuffing;
- spam/abuse and excessive contact frequency;
- accidental disclosure through logs, telemetry, exports, or error messages;
- software supply-chain compromise.

## Security invariants

These rules should remain true regardless of implementation details:

1. No tenant-scoped entity is accessible without an authenticated and authorized tenant context.
2. Tenant context is derived from trusted identity/membership state, never accepted blindly from a request parameter.
3. LLM output never directly causes an external side effect.
4. Every external action must pass deterministic authorization and policy checks.
5. Secrets never belong in prompts, application logs, frontend bundles, source control, or ordinary plaintext persistence.
6. Every outbound message is auditable and idempotent.
7. Opt-out / do-not-contact state overrides automation and AI recommendations.
8. Cross-tenant access must be blocked at both application and database levels where practical.
9. External provider input, including webhooks, is untrusted until verified and validated.
10. Secure behavior is the default; risky automation requires explicit enablement.

## Authentication and authorization

Authentication answers **who is this actor?**

Authorization answers **may this actor perform this action on this tenant/resource?**

They are separate concerns.

Initial roles may include:

```text
OWNER
ADMIN
OPERATOR
VIEWER
```

Capabilities should be more precise than roles where sensitive operations are involved, for example:

```text
CUSTOMER_READ
CUSTOMER_WRITE
MESSAGE_APPROVE
MESSAGE_SEND
TEMPLATE_WRITE
INTEGRATION_MANAGE
USER_MANAGE
BILLING_MANAGE
EXPORT_DATA
```

The backend is authoritative. The frontend may hide unavailable controls for usability, but UI visibility is never authorization.

## Tenant isolation

Tenant isolation uses two complementary layers:

- explicit application-level tenant scoping;
- PostgreSQL Row Level Security as defense in depth.

A request-provided `tenantId` must never be trusted as the source of tenant identity.

See [[Multi Tenancy and Data]].

## AI safety boundary

AI providers are treated as external untrusted systems.

The intended path is:

```text
trusted application context
      +
untrusted customer/imported content
      ↓
AI provider
      ↓
strict structured response
      ↓
schema validation
      ↓
policy / authorization / consent gate
      ↓
allowed application action
```

Prompt instructions are not security controls. Security comes from capability boundaries, authorization, allowlists, schemas, and deterministic policy.

The model must not be allowed to invent arbitrary provider actions, template identifiers, recipients, tenant identifiers, tool names, or authorization decisions.

## Human-in-the-loop

The safe default is:

```text
AUTO_SEND = false
```

A generated message should normally enter a state such as:

```text
DRAFT
  ↓
PENDING_APPROVAL
  ↓
APPROVED
  ↓
QUEUED
  ↓
SENT
  ↓
DELIVERED
```

with alternate states such as `REJECTED`, `CANCELLED`, and `FAILED`.

Future auto-send policies should be limited to explicitly defined low-risk scenarios and remain revocable through a global/tenant kill switch.

## Consent and contact policy

Consent is deterministic state, not an AI judgment.

Useful fields include:

- marketing consent status;
- consent source;
- consent timestamp;
- opt-out timestamp;
- do-not-contact state;
- channel eligibility;
- contact-frequency policy.

A `DO_NOT_CONTACT` condition must be a hard override.

## Secrets

Provider/API credentials must use a secret-management abstraction.

Application tables may store a **secret reference**, but should not store reusable provider credentials in plaintext.

Operational requirements include:

- least privilege;
- rotation;
- revocation;
- environment separation;
- no logging;
- no transmission to AI providers unless explicitly necessary and safe (normally it is not).

## Webhooks

Inbound provider webhooks must:

- verify provider signatures/authentication;
- validate schema;
- resolve the owning tenant from trusted integration metadata;
- reject malformed/unknown events;
- deduplicate by provider event/message identifiers;
- defend against replay where supported;
- process asynchronously where useful;
- avoid trusting webhook fields as authorization claims.

## Idempotency

Sending must remain safe under retries, scheduler duplication, process crashes, and network ambiguity.

A durable idempotency key/constraint should bind the logical follow-up/action to at most one unintended duplicate send.

## Audit

Security-sensitive and externally visible operations should create append-oriented audit records containing, as appropriate:

- tenant;
- actor type and actor identifier;
- action;
- entity/resource;
- timestamp;
- result;
- safe metadata.

Audit metadata must not become a second uncontrolled store of PII or secrets.

## Logging and observability

Do not log:

- passwords;
- session tokens;
- provider API keys;
- access/refresh tokens;
- full secret values;
- unnecessary customer PII;
- message bodies by default unless a deliberate policy requires them.

Phone numbers and identifiers should be masked where full values are unnecessary.

Metrics labels must not contain PII or unbounded customer-controlled values.

## Browser security

Where browser sessions are used, prefer secure cookie semantics:

- `HttpOnly`;
- `Secure`;
- appropriate `SameSite` policy;
- CSRF protection when the authentication model requires it.

Avoid long-lived sensitive tokens in `localStorage`.

Use exact CORS allowlists rather than permissive wildcards in authenticated environments.

## Rate limiting and abuse protection

Protect at minimum:

- login/authentication endpoints;
- password/reset flows;
- message send/approval paths;
- imports;
- exports;
- integration/webhook endpoints where applicable.

Outbound policy should detect or prevent anomalous bursts and repeated recipient contact.

## Supply chain

The repository should progressively enforce:

- Dependabot/Renovate-style dependency updates;
- dependency vulnerability scanning;
- secret scanning;
- CodeQL/SAST;
- SBOM generation for releases;
- container scanning;
- minimal/non-root runtime images;
- signed or otherwise verifiable release artifacts where practical.

## Database privileges

Runtime application credentials should not be database superusers.

Prefer separate responsibilities such as:

```text
migration_user
application_user
(optional) read_only_user
```

The runtime application account should not have unrestricted `DROP`/`ALTER` privileges.

## Incident containment

Dokene should support a fast containment path, including the ability to disable outbound messaging globally or per tenant without requiring code deployment.

Incident-response guidance should cover credential revocation, integration disablement, audit preservation, customer-impact analysis, and recovery.
