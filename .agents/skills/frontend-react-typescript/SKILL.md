---
name: frontend-react-typescript
description: Use when implementing or reviewing React and TypeScript frontend code, UI state, API integration, accessibility, browser security, performance, or tests.
---

# React and TypeScript Frontend

## Context

Read `AGENTS.md`, `.agents/README.md`, frontend package/build files, affected source/tests, relevant ADRs, and API contracts before changing frontend code.

## Baseline

- TypeScript is the implementation language.
- React is the UI framework.
- Vite is the build tool unless an ADR states otherwise.
- Prefer strict typing and explicit API contracts.

Use platform and framework capabilities when they improve correctness and maintainability rather than introducing dependencies by default.

## TypeScript Rules

- Keep `strict` mode enabled.
- Avoid `any`; use `unknown` at untrusted boundaries and narrow explicitly.
- Prefer discriminated unions for finite UI/domain states.
- Prefer readonly/immutable values where mutation is unnecessary.
- Model impossible states out of the type system when practical.
- Do not duplicate backend DTO definitions manually when generated/contract-derived types are available.
- Avoid non-null assertions unless the invariant is locally obvious and justified.
- Use type assertions sparingly; assertions do not validate runtime data.

## React Design

- Organize by feature/domain rather than one global directory per technical primitive when the application grows.
- Keep components focused; split responsibilities when rendering, data access, orchestration, and business rules become entangled.
- Keep business/domain rules outside presentation components where practical.
- Prefer derived state over duplicated synchronized state.
- Avoid effects for calculations that can happen during render or in event handlers.
- Effects that interact with external systems must define lifecycle/cleanup deliberately.
- Keep server state and local UI state conceptually separate.
- Do not introduce global state management unless state ownership genuinely crosses feature boundaries.
- Prefer composition over deeply configurable "god components".

## API Integration

- Treat all API/network data as untrusted at runtime even when TypeScript types exist.
- Prefer a generated client or contract-derived types from the backend OpenAPI specification when available.
- Centralize transport concerns such as base URL, credentials, headers, error mapping, and cancellation.
- Do not scatter raw `fetch` calls across presentation components.
- Model loading, empty, success, authorization-denied, validation-error, and dependency-failure states explicitly.
- Use abort/cancellation for requests whose results become irrelevant after navigation or input changes.
- Never infer authorization from hidden UI controls; the backend remains authoritative.

## Browser Security

Security is a first-class requirement.

- Never embed API keys, provider secrets, service credentials, or privileged tokens in frontend bundles.
- Avoid storing long-lived sensitive authentication tokens in `localStorage` when secure server-managed/session-cookie patterns are available.
- When cookies are used for authentication, respect `HttpOnly`, `Secure`, `SameSite`, and CSRF protections defined by the backend architecture.
- Do not render untrusted HTML. Avoid `dangerouslySetInnerHTML`; if unavoidable, use a reviewed sanitization strategy and document the trust boundary.
- Treat URL/query/hash values as untrusted input.
- Do not expose sensitive data in client logs, analytics events, error reports, or browser persistence unnecessarily.
- Do not rely on obscurity of route names, UUIDs, or disabled buttons for authorization.
- Avoid permissive dynamic script execution and unsafe-eval patterns.

## Tenant and Identity UX

For multi-tenant applications:

- do not allow arbitrary client-controlled tenant IDs to become authorization context;
- tenant switching must use authenticated backend-supported membership state;
- clear tenant-scoped caches/state when the active tenant changes;
- do not merge data from two tenants in shared client caches;
- ensure optimistic updates and query keys include the proper tenant scope where applicable.

## Forms and Validation

- Use client-side validation for usability, not as a security boundary.
- Mirror user-facing constraints where helpful but assume the backend re-validates everything.
- Preserve server validation messages in a safe, structured form.
- Prevent accidental double submission for non-idempotent actions.
- Make destructive or high-impact actions explicit and recoverable where reasonable.

## Accessibility

Accessibility is part of correctness.

- Prefer semantic HTML before ARIA.
- Ensure interactive elements are keyboard accessible.
- Provide visible focus behavior.
- Associate labels with controls.
- Preserve appropriate heading hierarchy.
- Announce asynchronous validation/status changes when necessary.
- Do not encode state or meaning by color alone.
- Test critical flows with keyboard navigation and, when practical, automated accessibility tooling.

## Performance

- Optimize based on measured user impact, not reflexive memoization.
- Avoid unnecessary rerenders caused by duplicated state or unstable ownership.
- Use code splitting/lazy loading for meaningful route/feature boundaries when it improves load cost.
- Keep bundle growth visible when adding dependencies.
- Do not use `useMemo`, `useCallback`, or memoized components everywhere by default; apply them where identity or measured rendering cost matters.
- Avoid shipping large libraries for trivial utilities.

## Error Handling and UX

- Present actionable user-facing errors without exposing stack traces or internal implementation details.
- Distinguish authorization failure, validation failure, network/dependency failure, and unexpected faults.
- Preserve retry semantics only when retry is safe.
- Use error boundaries for render failures at appropriate application/feature boundaries.
- Never silently swallow failures that affect user trust or data consistency.

## Testing

Test behavior rather than implementation details.

Cover relevant cases such as:

- successful user flow;
- loading/empty/error states;
- form validation;
- unauthorized/forbidden behavior;
- tenant changes/cache isolation;
- duplicate submission prevention;
- accessibility of critical interactions;
- API failure and retry behavior.

Prefer tests that exercise components through accessible roles/labels and user-visible behavior.

Before completion, run the relevant frontend tests/build/lint/type checks defined by the repository and document any intentionally skipped verification.

## Review Checklist

Verify:

- TypeScript remains strict and avoids unsafe assertions/`any`;
- components have clear responsibilities;
- state ownership is explicit and not duplicated unnecessarily;
- API access is centralized and contract-aligned;
- no secrets or unnecessary PII reach the browser;
- tenant-scoped state cannot bleed across tenant changes;
- authorization is not implemented only in the UI;
- untrusted HTML/URL/API data is handled safely;
- critical interactions are accessible;
- dependency/bundle cost is justified;
- tests cover important failure and security cases;
- durable architecture changes are documented.
