# ADR 0021: Synthetic AI Evaluation Harness and Phase 2 Quality Baseline

## Status

Accepted. Amends [ADR 0017](0017-deterministic-ai-action-gate.md) (recommendation rationale and draft variables, and refusal rationales, are content-validated like drafts) and [ADR 0018](0018-constrained-follow-up-message-draft-generation.md) (customer notes no longer ground offers, prices or discounts; only purchase descriptions do). Builds on [ADR 0004](0004-ai-action-gate.md), [ADR 0015](0015-structured-next-best-action-recommendation-contracts.md), [ADR 0016](0016-openai-responses-api-adapter-with-structured-outputs.md) (CI never uses a live provider or key), [ADR 0017](0017-deterministic-ai-action-gate.md), [ADR 0018](0018-constrained-follow-up-message-draft-generation.md) and [ADR 0019](0019-ai-failure-handling-telemetry-and-audit.md).

## Context

[Issue #98](https://github.com/stevdrey/dokene/issues/98). Phase 2 produces recommendations and drafts through an untrusted model behind a deterministic Action Gate. Changing the model, the prompt/context policy or the structured contract can change safety and quality in ways unit tests do not show, and there was no repeatable way to measure that separately from platform correctness. Quality must not be judged by exact text, a single aggregate score, or real customer data.

## Decision

### 1. Hard invariants are pass/fail and independent from quality

`InvariantChecker` (test source set) evaluates, on what an operator would actually receive after the gate (the *delivered layer*): schema validity (re-parse through the strict contract parsers), action/template-intent allowlist compliance, no content or provider call when deterministic policy forbids contact (consent revoked/unknown, do-not-contact, archived), no invented provider template ID, no unsupported link/discount/price, bounded length, and gate-outcome safety (no unsafe raw output accepted). Content checks cover every operator-visible text (draft, recommendation rationale and draft variables, both refusal rationales); amounts are compared as complete tokens against the purchase descriptions and links are detected in every form the production validator rejects. They must hold at 100% for every provider and model, and are never traded against language quality. The patterns are deliberately independent from the production `DraftSafetyValidator` so a validator regression is detected rather than trusted; text grounded in purchase descriptions is allowed, text only present in customer notes is not (notes are untrusted).

Raw (pre-gate) model findings — schema-invalid output, allowlist violations, unsafe drafts, refusals, provider failures — are reported separately as comparison data. Unsafe model output is expected test input; the deterministic controls must reject it.

### 2. Quality is a rubric, not a score

Recommendation relevance, rationale usefulness, draft quality, factual grounding and editability/tone are graded 1–5 by humans against documented anchors and recorded in a per-case `rubric` block of the report. No exact-text matching and no aggregate "best model" number is produced. A few labelled informational heuristics (locale, name present, body length) never gate anything.

### 3. Versioned synthetic dataset, real stack

`src/test/resources/ai-eval/v<major>/dataset.json` is semantically versioned, declared `syntheticOnly`, and covers the eleven scenario families of the issue (repeat purchase, dormant, recent purchase, explicit next follow-up, consent revoked, do-not-contact, archived, missing facts, adversarial notes, Spanish wording, unsupported action/template). Names are marked `Demo-NN`, hosts use reserved `.test` domains and no emails/phones appear; a test guards this. The runner seeds an isolated synthetic tenant per run and calls the production `FollowUpRecommendationService`, `FollowUpDraftService`, `DefaultAiActionGate`, PostgreSQL and RLS, so consent/DNC/archived are evaluated by the authoritative policy. Only the `AiProvider` differs.

### 4. Deterministic mode in CI, live mode opt-in

- **Deterministic** (`AiEvalDeterministicIntegrationTest`, part of `./gradlew test`): a scripted provider replays per-case output, including deliberately unsafe output, with constant latency/usage. The report must match the committed baseline `ai-eval/baselines/deterministic-v1.json` except for the timestamp, so gate, contract or dataset drift fails CI. No key, no network, no cost.
- **Live** (`./gradlew aiEvalLive`, tag `ai-eval-live`): excluded from `test` and from `check`, additionally skipped unless `DOKENE_AI_EVAL_LIVE=true` and `DOKENE_AI_OPENAI_API_KEY` are set locally. It sends only synthetic data through the real adapter, records provider/model as returned, latency, token usage and (only if a price table is supplied) cost, and fails only on delivered-layer hard-invariant violations.

### 5. Reports and comparison

Each run writes `build/reports/ai-eval/<name>.json` (machine-readable) and `.md` (human-readable), including the content delivered after the gate so humans can grade it later (model output about invented synthetic customers only). The report carries the dataset version, a canonicalized fingerprint of the strict structured-output schemas, an optional prompt/context policy label and per-case status/rejection reasons. `./gradlew aiEvalCompare -Pbaseline=… -Pcandidate=…` shows invariant, raw-finding, latency, token and cost deltas side by side and exits non-zero on any hard-invariant regression, including a baseline invariant that is missing from the candidate; it warns when dataset version or contract fingerprint differ. Before changing model, prompt/context policy or contract, capture a baseline with the current configuration and compare.

## Consequences

- Eval output informs a human decision; it never triggers a rollout, and no model is declared best from one number.
- The deterministic baseline measures platform controls (contract, gate, policy), not model quality. Model quality requires live runs plus human grading.
- Free-text product claims remain a residual risk (ADR 0018): the invariants cannot prove a draft's factual claims, only that it contains no links, template IDs, prices or discounts that are not grounded.
- The harness lives in the test source set; promoting it to production code would need a new ADR.
- Evaluation configures a connection pool larger than the shared integration fixture's default of one (gate-rejection audits use an independent transaction) and a high AI rate limit for the single synthetic actor; rate limiting is verified elsewhere.

- Evaluation found that the gate did not validate recommendation rationale/draft variables or refusal rationales; `DefaultAiActionGate` now applies `DraftSafetyValidator` to them.

## Out of scope

Training or fine-tuning, real customer conversations or production prompts, automatic production rollout from evaluation results, and an LLM-as-judge grader.
