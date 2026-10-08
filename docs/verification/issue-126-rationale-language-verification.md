# Issue 126 — Recommendation rationale language verification

## Change

- NBA (`SYSTEM_INSTRUCTIONS`) and draft (`DRAFT_SYSTEM_INSTRUCTIONS`) prompts now require operator-facing `rationale` (and draft `warnings`) in Latin American Spanish (`es-419`); enum values are unchanged.
- `rationale`/`warnings` JSON Schema descriptions carry the same hint (contract fingerprint changed, deterministic baseline regenerated).
- Fake provider and the adapter's locale-refusal rationales are Spanish.
- Evaluation harness records the informational `recommendationRationaleLooksSpanish` / `draftRationaleLooksSpanish` heuristics. They never gate (language quality stays a human rubric, ADR 0021).

## Evidence

- `OpenAiResponsesApiAdapterTest` asserts the request carries the Spanish requirement for both operations.
- `RationaleLanguageTest` covers the heuristic; the deterministic baseline shows all delivered rationales as Spanish.
- Not run: a live OpenAI journey (needs key and budget). Residual risk: the model may still answer in English occasionally; re-measure with `./gradlew aiEvalLive` and inspect the new informational flags.
