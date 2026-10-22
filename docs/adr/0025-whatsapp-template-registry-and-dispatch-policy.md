# ADR 0025: WhatsApp Template Registry, Provider Approval Evidence and Dispatch Policy

## Status

Accepted. Builds on [ADR 0023](0023-phase-3-outbound-messaging-contracts.md) §3.2 and [ADR 0024](0024-phase-3-messaging-contract-freeze.md) §3. It refines one detail of ADR 0023 §3.2 (the precondition of `PUT /api/templates/{templateIntent}`, see §3) and does not change module ownership, the permission matrix or the state machine. Issues: [#215](https://github.com/stevdrey/dokene/issues/215) (registry foundation) and [#135](https://github.com/stevdrey/dokene/issues/135) (provider verification), part of epic [#130](https://github.com/stevdrey/dokene/issues/130).

## Context

A follow-up message reaches a customer only as an approved provider template. The AI selects a closed `SemanticTemplateIntent`; it never sees or produces a provider template name or id (ADR 0018 §4). The application therefore needs a deterministic, tenant-owned mapping from intent to provider template, and it must not treat a mapping as sendable on the strength of anything an operator typed. Three questions had no answer yet:

1. What a mapping stores, and what makes it eligible.
2. Where "approved by Meta" comes from, and how long it stays valid.
3. When a free-form message is allowed instead of a template.

## Decision

### 1. Registry

`template` owns `tenant_provider_templates` (migration V21): one row per `(tenant, semantic intent)` with the provider template name (`^[a-z0-9_]+$`, at most 512 characters), the template language (Meta grammar, for example `es` or `es_MX`), up to ten ordered unique parameter names, an `enabled` flag, the integration it belongs to, a `version` (the mapping revision) and the approval evidence of §2. Rows are under forced row-level security. The integration reference is a composite foreign key `(tenant_id, integration_id)`, so a mapping can only name its own tenant's integration.

Parameters are text. `TemplateParameterValues.validate` checks that a rendered value list has exactly the schema's count and that every value is 1 to 1024 characters with no control characters, no Unicode format characters (except zero-width joiners and non-joiners, and the emoji tag characters used by subdivision flags) and no run of more than four spaces (Meta's body-parameter limits). Dispatch (#142) calls it after rendering and before building the send command.

`TEMPLATE_READ` reads the catalogue and `TEMPLATE_WRITE` edits it and refreshes approval. The role map is unchanged. A template write never touches the integration record: it only reads the sender identity through `TemplateProviderGateway`, so template writers gain no integration-management right.

The intent in the path is the closed enum (`404` otherwise). The request body has no member for a provider template id, and unknown members are `400`, so a model-supplied or client-supplied id has no way in.

### 2. Approval evidence

Approval is evidence, not configuration:

- The request body cannot carry it.
- Editing a mapping sets its status to `UNVERIFIED` and advances `version`.
- Only `POST /api/templates/{templateIntent}/approval-refresh` (`TEMPLATE_WRITE`, strong `If-Match`) records it. That call asks the provider through `TemplateProviderGateway`, implemented in `integration.whatsapp` by `GraphApiTemplateProviderGateway`.
- The gateway reads `GET {graph}/{businessAccountId}/message_templates?name=…&language=…&fields=name,language,status,parameter_format,components` with the tenant's own credential, resolved through `IntegrationSecretResolver` at call time. The credential is held only for that call and goes only into the `Authorization` header.
- Only a `200` whose `data` contains the exact name and language is evidence. Meta's `APPROVED` is the only status that can make a mapping sendable, and only after its parameter schema matches the mapping (§2a). `PENDING` and `IN_APPEAL` store `PENDING`. `REJECTED`, `PAUSED` and `DISABLED` store their own state. `DELETED`, `PENDING_DELETION` and `ARCHIVED` store `DELETED`. A response without the template stores `NOT_FOUND`.
- The endpoint host is allow-listed because every tenant bearer token is sent to it: `graph.facebook.com` over HTTPS, loopback test servers, or a host listed in `dokene.integration.whatsapp.graph.additional-trusted-hosts`. That list is a trust boundary (each entry receives tenant credentials, so it is for an internal HTTPS proxy or mock the deployment controls) and is empty by default. A rejected configuration stops the application at startup, so no credentialed request is ever made. The response is read with a hard 256 KiB and timeout bound while streaming, never after buffering.
- The lookup follows Graph cursors (`paging.cursors.after`) on the same base URL for at most five pages, never the `paging.next` URL a response supplies, and shares one deadline across all pages. Absence is concluded only when the list really ends; with pages still pending it is unavailable.
- Anything else is *unavailable*: other status codes, redirects (not followed), timeouts, unreadable bodies, `LIMIT_EXCEEDED` or an unknown status, no secret resolver bean, an unresolvable secret. No provider text is kept or returned.
- An unavailable answer drops earlier evidence (status `UNVERIFIED`) and the call answers `503 PROVIDER_UNAVAILABLE`, so a failure can never leave an old `APPROVED` standing.

The evidence row stores the status, the check time, the business account that was asked, the mapping `version` it was checked against and, for `APPROVED` only, the verified parameter format. A refresh does not advance `version`, so recording evidence never voids open approvals.

`RegistryTemplateMappingResolver` implements the frozen `TemplateMappingResolver` port and answers from stored evidence only, with no network call. It returns `Resolved` only if all of these hold:

- the mapping is `enabled`;
- its status is `APPROVED`, which implies a matching parameter schema (§2a);
- the evidence was recorded for the current `version`;
- the mapping belongs to the tenant's current integration;
- the evidence was recorded for that integration's current business account;
- the evidence is no older than `dokene.template.approval-max-age` (default 24 hours).

An absent mapping is `NotMapped`. Everything else is `Disabled`. Submit already calls it through `ResolverBackedTemplateMappingGate`, which replaces the property-driven stub. Approval ([#136](https://github.com/stevdrey/dokene/issues/136)) and send ([#142](https://github.com/stevdrey/dokene/issues/142)) must call the same resolver, so that a stale, disabled, deleted, rejected or edited mapping is ineligible at every step. Refresh is explicit. There is no scheduler in this phase: an operator refreshes before use, and an expired mapping simply stops being sendable until someone does.

The approval fingerprint (ADR 0024 §2) binds the mapping `version`, name, language, verified parameter format and parameter names. An edit therefore voids open approvals with `APPROVAL_INVALIDATED`, while a refresh or an expiry makes the next step fail closed with `TEMPLATE_NOT_MAPPED`.

### 2a. Parameter schema evidence (issue 218)

Provider approval alone does not prove the template can receive what Dokene sends. When Meta reports `APPROVED`, `GraphApiTemplateSchemaReader` reads the matched template's `parameter_format` and `components` into a provider-neutral `ProviderTemplateSchema`, and the template domain compares it with the stored `parameterNames`. Dokene sends text parameters in the body only.

- `parameter_format` absent means positional (Meta's default). `POSITIONAL` and `NAMED` are accepted; any other value is unreadable.
- `components` must be an array of 1 to 10 objects, each with a string `type`. Exactly one `BODY` with a string `text` is required.
- A `HEADER` is accepted only as `format: TEXT` without placeholders; a media, location or any other header is unsupported. A `FOOTER` is accepted without placeholders. `BUTTONS` are accepted only as `QUICK_REPLY`, `PHONE_NUMBER` or a `URL` without a placeholder. Any other button, any component needing a value and any unknown component type is unsupported.
- Placeholders are scanned with fixed bounds (at most 32 tokens of at most 64 characters, text of at most 4096 characters); malformed braces are unreadable.
- Positional placeholders must be exactly `{{1}}` to `{{n}}`, with `n` equal to the number of parameter names. Named placeholders must be exactly the stored names. Repeated placeholders are unsupported. A template without placeholders is recorded as `POSITIONAL` whatever it declares, because no parameter is sent.

The outcome is recorded as evidence like any provider status. Only a match stores `APPROVED`, together with the verified format. Otherwise the closed status is `SCHEMA_UNREADABLE`, `SCHEMA_UNSUPPORTED` or `SCHEMA_MISMATCH`, in that order of precedence, and is never sendable. These statuses are derived by Dokene: the provider gateway cannot report them, and the request body cannot carry them. The refresh answers `200` with `sendable: false`; a failure to obtain the template at all is still *unavailable*. Neither the provider's text nor the placeholders are stored, logged, audited or returned. *Superseded in part by [#268](https://github.com/stevdrey/dokene/issues/268):* an `APPROVED` refresh now stores the template's fixed header, body and footer text and its accepted buttons so review can show them (see the addendum for issue 268). Placeholders stay unresolved in that text, and it is still never logged or audited.

Migration V27 adds the statuses and the columns `approval_parameter_format` and `approval_parameter_checked_at`, and returns every existing `APPROVED` row to `UNVERIFIED`, because that evidence never checked the schema. Operators refresh those mappings again. A refresh that changes the verified format is audited like a status change (§3).

V27 is an expand-only migration, so a rolling deploy is safe. Instances of the previous release never write the new columns or statuses, and no constraint refuses their writes. A stored format is trusted only for `APPROVED` evidence whose `approval_parameter_checked_at` equals `approval_checked_at`. An older instance that rewrites the evidence (a status-only refresh, or an edit) changes the check time without the format, so its approval reads as schema-unchecked and is not sendable until a current instance refreshes it. During the deploy window, an older instance that reads a `SCHEMA_*` row fails with an error instead of answering; that is closed and sends nothing. Once every instance runs this release, a later migration may add the strict "format exactly when `APPROVED`" constraint and drop the check-time column.

Both verified formats are sendable. The resolver returns the format with the mapping, the approval fingerprint binds it (`approval/v3`, ADR 0024 addendum for issue 218), and the send adapter builds the parameters in that shape: positional values in order, or for `NAMED` each value with its `parameter_name`. The adapter re-checks every name against the mapping's parameter-name grammar and refuses a malformed one with `TEMPLATE_REJECTED` before any call.

### 3. Precondition and audit

ADR 0023 §3.2 named a tenant-wide catalogue version as the `If-Match` value. This ADR uses the mapping's own `version` instead, because that revision is the one the fingerprint binds and a second counter would have to be kept in step with it. `GET /api/templates` therefore carries no ETag. Each entry carries its `version`, and `PUT` and `approval-refresh` return it as the strong ETag. Creation uses `If-None-Match: *`, exactly like the integration endpoint. Responses are `Cache-Control: no-store`.

`TEMPLATE_MAPPING_UPDATED` (already in the closed audit set, ADR 0023 §4.5) is emitted in the same transaction for every create or edit and for every refresh that changes the status or the verified parameter format. Its metadata is the target mapping UUID and the `enabled` flag. Names, languages, parameters, statuses and provider text are never audit data. If audit fails, the change rolls back (`503`).

### 4. Free-form versus template dispatch policy

Meta's current documentation (WhatsApp Cloud API, "Send messages", checked when this ADR was written) says:

- a customer service window opens when the user messages the business and lasts 24 hours, restarting on each new user message;
- inside it any message type can be sent, with no pre-approved template;
- outside it only pre-approved template messages can be sent;
- opt-in is required either way.

Dokene's policy:

- **Phase 3 sends only approved templates.** Every outbound message is a business-initiated follow-up. Inbound customer messages are out of scope (ADR 0023), so the application has no trusted evidence of an open window. Without trusted evidence the policy fails closed to a verified template, and free-form text is never sent.
- A future inbound path may enable free-form replies only when the window is derived from a verified inbound webhook event of the same tenant and customer, with the 24 hours measured from that event's provider timestamp. A client flag, an operator choice and a model output are not evidence. That change needs its own ADR.
- The body of a message is still the audited draft (ADR 0023 §2). The template carries the parameter values the approval bound.

## Alternatives considered

- **Let `TEMPLATE_WRITE` set the status.** Rejected: the issue forbids operator-supplied approval flags, and the field would be unverifiable.
- **Look the status up on every send.** Rejected: it puts a network call in the send path and its failure modes, and the issue asks for an explicit refresh.
- **A recurring sync job.** Out of scope for this phase (explicit in the issue).
- **A tenant-wide catalogue version.** Rejected for §3.
- **A new audit event for refresh.** Rejected for now: it needs an audit-shape migration. The status change is still audited through `TEMPLATE_MAPPING_UPDATED`.
- **Free-form within an operator-asserted window.** Rejected: that is an operator-supplied flag in another form.

## Consequences

- Approved evidence proves the provider's status, name and language and, since [#218](https://github.com/stevdrey/dokene/issues/218), that the template's components and placeholders match the stored parameter names (§2a). Real outbound sending is therefore never enabled on status, name and language alone.
- Delivery was split to respect the 40-file PR limit. [#215](https://github.com/stevdrey/dokene/issues/215) added the registry, resolver and API with a fail-closed provider gateway; [#135](https://github.com/stevdrey/dokene/issues/135) adds `GraphApiTemplateProviderGateway` (the provider lookup of §2) and replaces the submit-time stub with `ResolverBackedTemplateMappingGate`.
- `GraphApiTemplateProviderGateway` is the only code that calls Meta for templates. Until a production `IntegrationSecretResolver` exists, every refresh is unavailable and no template becomes sendable, so sending stays disabled even if the other pieces are in place. That is the intended fail-closed default.
- Templates created in Meta's WhatsApp Manager stay the source of truth for content and approval. Dokene stores a pointer and the last evidence only; it does not submit templates for approval.
- #136 (submit and approval) and #142 (dispatch) consume `TemplateMappingResolver` and `TemplateParameterValues`. They map the message locale to the template language, because Meta's language codes (`es_MX`) differ from the BCP 47 locale in the message contract (`es-419`).
- A `TemplateErrorCode` enum in `template` repeats the subset of `MessagingErrorCode` that this API raises, because `template` may not depend on `messaging` (ADR 0024 §3). `TemplateErrorCodeParityTest` pins names, statuses and titles to the messaging contract.

## Addendum (issue 268): template content snapshot

[#268](https://github.com/stevdrey/dokene/issues/268) lets an operator send a template whose content is fixed, starting with Meta's sample `hello_world` (no parameters). The reviewer must see what the recipient will read, and the approval must bind it. This part stores and binds the template text; the English locale follows in a second change.

- **Content snapshot.** An `APPROVED` refresh stores the template's fixed `HEADER` (text format), `BODY` and `FOOTER` text and its accepted buttons with the evidence (V30: `approval_header_text`, `approval_body_text`, `approval_footer_text`, the aligned arrays `approval_button_types`, `approval_button_texts`, `approval_button_values`, and `approval_content_checked_at`). A button is a `QUICK_REPLY` (label only), a `PHONE_NUMBER` (label and number) or a static `URL` (label and URL), in the provider's order, at most ten. Each part is at most 4096 characters, the body is required, and blank or NUL-bearing text, or a button without its label or target, makes the schema unreadable. The recipient sees and can use the buttons, so they are reviewed and bound like the text. The text is dropped with the evidence on every edit, non-approved refresh or unavailable answer. An approval without content is never sendable, so V30 returns every `APPROVED` row to `UNVERIFIED` and operators refresh once. V30 is expand-only like V27 and fails closed in both directions during a rolling deploy. Content counts only when `approval_content_checked_at` equals `approval_checked_at`, so an approval rewritten by an instance of the previous release reads as having no content and is not sendable until refreshed again. In the other direction, current instances record the verified format in `approval_content_parameter_format`, together with the content, and leave the V27 columns `approval_parameter_format` and `approval_parameter_checked_at` empty. The previous release trusts only those V27 columns, so to it a current approval has no verified format and is never sent: an old instance cannot approve or send under `approval/v3` while ignoring the fixed content.
- **Review shows what is sent.** `GET /api/templates` returns the content (`content`, null without `APPROVED` evidence), with `buttons` always present (`[]` when there are none). Placeholders stay as written; their values are bound separately.
- **Body equals the fixed text.** When a mapping's parameter names do not include `message_body`, the body is never sent, so submit, approval and send require the body to equal the snapshot body text exactly (`BODY_REJECTED` otherwise). What is reviewed, safety-checked and approved is then what the recipient reads, and static template content is never presented as free editable text. A message body is at most 1000 characters, so such a template whose fixed body is longer is recorded as `SCHEMA_UNSUPPORTED` at refresh instead of becoming sendable but unusable. The template module mirrors the `message_body` name and the limit, and `FixedTemplateBodyParityTest` pins both to the messaging rules.
- **One header, footer and button group.** A second `HEADER`, `FOOTER` or `BUTTONS` component makes the schema unreadable, like a second `BODY`, so the stored and approved content can never silently miss a component.
- **Contract.** `content` is an optional member in v1 (ADR 0024): the backend always emits it, and the frontend reads a response without it, from a backend older than this change, as `null`.
- **Approval binding** (ADR 0024, addendum for issue 268). The fingerprint scheme is `approval/v4`: the template revision now includes the header, body and footer text and the buttons. A text change on Meta's side followed by a refresh voids open approvals (for a template without `message_body` the stored body no longer matches, which is `BODY_REJECTED`). Approvals computed under `approval/v3` no longer match and are refused with `APPROVAL_INVALIDATED`; the message has to be cancelled and submitted again. Because V30 also resets every approved mapping, no v3 approval can be sent before a refresh anyway.
- **Dispatch.** Unchanged: the Graph adapter omits `components` for a template without parameters.
