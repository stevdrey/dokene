# ADR 0015: Structured Next Best Action Recommendation Contracts

## Status

Accepted

## Context

Following deterministic follow-up eligibility ([ADR 0012](0012-deterministic-follow-up-eligibility.md)) and the operator-facing due queue ([ADR 0013](0013-due-follow-up-queue-and-operator-dispositions.md)), Dokene enters **Roadmap Phase 2 — AI-assisted recommendations**.

Under [ADR 0004: AI Action Gate](0004-ai-action-gate.md), model output is strictly advisory and untrusted. The application core owns policy, tenant boundaries, consent, and execution. Free-form prose from language models cannot be parsed into executable commands, nor can a model decide who is eligible for contact, invent provider template identifiers, or bypass operator review.

To enable AI-assisted decision support, Dokene requires strongly typed, provider-neutral recommendation contracts that compile under Java 26, model application-owned concepts, separate deterministic facts from advisory suggestions, and serialize to strict JSON Schemas suitable for modern Structured Outputs provider adapters.

## Decision

### 1. Closed Vocabulary and Application Ownership

Recommendation concepts are defined as closed domain types in the `ai` module under `io.github.stevdrey.dokene.ai.domain` (aligning with `docs/wiki/Architecture.md` and `backend/README.md`):

- **Semantic Action (`SemanticAction`)**: Allowlisted follow-up actions owned by Dokene (`REPEAT_PURCHASE_FOLLOW_UP`, `GENERAL_CHECK_IN`, `RELATED_PRODUCT_OFFER`, `DORMANT_REENGAGEMENT`, `SEASONAL_GREETING`). Unknown values fail fast with `RecommendationValidationException` and are never coerced or defaulted.
- **Semantic Template Intent (`SemanticTemplateIntent`)**: Allowlisted message intents (`GENERAL_FOLLOW_UP`, `REPEAT_PURCHASE`, `RELATED_PRODUCT`, `SEASONAL_EVENT`, `DORMANT_CUSTOMER`). Provider-specific template identifiers (e.g. Meta WhatsApp Cloud API template IDs) are mapped deterministically by the application core and never invented or specified by the model.
- **Explicit Refusal / No-Recommendation (`NoRecommendationReason`)**: Closed reasons explaining why no action was advised (`INSUFFICIENT_HISTORY`, `RECENTLY_CONTACTED`, `NO_RELEVANT_OFFER`, `UNCERTAIN_INTENT`, `MANUAL_REVIEW_REQUIRED`), avoiding placeholder actions or ambiguous empty responses.
- **Bounded Confidence (`RecommendationConfidence`)**: A finite score strictly validated within `[0.0, 1.0]`.
- **Bounded Draft Variables (`DraftVariables`, `DraftVariableEntry`)**: Structured draft inputs for template rendering with strict boundaries: at most 20 entries, key matching `^[a-zA-Z0-9_]{1,50}$`, value maximum 500 characters, no duplicates.

### 2. Sealed Hierarchy for Advisory AI Output

Model output is represented by the sealed interface `RecommendationOutcome`:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ActionRecommendation.class, name = "ACTION"),
    @JsonSubTypes.Type(value = NoRecommendation.class, name = "NO_RECOMMENDATION")
})
public sealed interface RecommendationOutcome permits ActionRecommendation, NoRecommendation {
    String rationale();
    RecommendationConfidence confidence();
}
```

- `ActionRecommendation`: Carries the recommended `SemanticAction`, `SemanticTemplateIntent`, bounded `rationale` (max 500 chars), `RecommendationConfidence`, and `DraftVariables`.
- `NoRecommendation`: Carries `NoRecommendationReason`, bounded `rationale` (max 500 chars), and `RecommendationConfidence`.
- Java 26 exhaustive pattern matching guarantees compile-time verification when consuming outcomes across application services and operator views.

### 3. Separation of Authority: `FollowUpDecision`

Authoritative business decisions combine deterministic eligibility and untrusted advisory recommendations via `FollowUpDecision`:

1. **Deterministic Facts (Authoritative)**:
   - `customerId`, `eligible`, `followUpStatus`, `eligibilityReasons`, `tenantDate`, `tenantZone`, `evaluatedAt`, `nextFollowUpDate`, `timingSource`, `effectiveCadenceDays`, `lastPurchaseAt`.
   - Originates strictly from `FollowUpEvaluation` ([ADR 0012](0012-deterministic-follow-up-eligibility.md)).
2. **Advisory AI Fields (Untrusted)**:
   - `recommendation` (`RecommendationOutcome`, optional).
3. **Inviolable Invariant**:
   - Model output can never override deterministic eligibility.
   - Constructing a `FollowUpDecision` with an `ActionRecommendation` for a customer who is deterministically ineligible (`!eligible`) throws `RecommendationValidationException`.

### 4. Strict Schema Generation for Structured Outputs
 
The `RecommendationJsonSchema` generator produces strict, provider-neutral JSON Schemas compatible with OpenAI Structured Outputs (`strict: true`) and equivalent constrained decoding engines:
 
- `additionalProperties: false` is enforced on all object schemas.
- All defined properties are declared in the `required` array.
- Enums are strictly populated from the Java domain enums.
- Draft variables are modeled as typed key-value item arrays, preventing dynamic map validation rejections.
- Wire transport envelopes (such as OpenAI's `response_format = { type: "json_schema", ... }`) belong exclusively to provider adapters and do not leak into the domain contract.

## Consequences

- **Security & AI Action Gate**: The AI model functions strictly as an untrusted advisory assistant. It cannot initiate actions, send messages, or modify customer eligibility.
- **Provider Neutrality**: No OpenAI, Anthropic, or Meta WhatsApp SDK classes or provider DTOs leak into domain or application contracts.
- **Fail-Fast Integrity**: Unknown actions, malformed templates, out-of-bounds confidence scores, or oversized rationales fail immediately rather than propagating subtle runtime bugs.
- **Operator Explainability**: Every recommendation carries a human-readable, bounded rationale and confidence score intended for operator review.
