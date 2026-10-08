# Issue 126 — Recommendation rationale language verification

## Change

- NBA and draft prompts are now built from the operator locale (`systemInstructions(locale)`, `draftSystemInstructions(locale)`) and require operator-facing `rationale` (and draft `warnings`) in that locale (`es-419` today); enum values are unchanged. They also forbid mentioning offers/prices/amounts in any language, even negated.
- `rationale`/`warnings` JSON Schema descriptions carry a language-neutral hint (contract fingerprint changed, deterministic baseline regenerated).
- Fake provider and the adapter's locale-refusal rationales are Spanish.
- Evaluation harness records the informational `recommendationRationaleLooksSpanish` / `draftRationaleLooksSpanish` heuristics. They never gate (language quality stays a human rubric, ADR 0021).

## Evidence

- `OpenAiResponsesApiAdapterTest` asserts the request carries the Spanish requirement for both operations.
- `RationaleLanguageTest` covers the heuristic; the deterministic baseline shows all delivered rationales as Spanish.
- Not run: a live OpenAI journey (needs key and budget). Residual risk: the model may still answer in English occasionally; re-measure with `./gradlew aiEvalLive` and inspect the new informational flags.

## Live finding (local dev-env, live OpenAI)

A first version asked only for Spanish. Live, the draft failed intermittently (`INVALID_STRUCTURED_RESPONSE`, rejection `UNSAFE_CONTENT` / `HALLUCINATED_OFFER_TERM`): the model wrote rationales such as "sin agregar ofertas", and the Spanish safety validator treats any ungrounded offer word in operator-visible text as a hallucinated offer. Fixed at the source with the language-neutral prohibition above rather than by loosening the validator (an existing test requires rejecting a rationale that affirms an offer).
