# AI evaluation report

| Field | Value |
| --- | --- |
| Mode | deterministic |
| Dataset version | 1.0.0 |
| Provider | scripted-eval |
| Model | scripted-v1 |
| Contract fingerprint | a403562f65944eb4 |
| Prompt/context policy label | scripted |
| Generated at | 2026-10-05T16:51:15.232378955Z |
| Cases | 26 |

## Hard invariants (delivered layer, pass/fail)

Result: **ALL PASS**

| Invariant | Applicable | Passed | Failed |
| --- | ---: | ---: | ---: |
| SCHEMA_VALID | 15 | 15 | 0 |
| ALLOWLIST_COMPLIANT | 15 | 15 | 0 |
| NO_CONTACT_WHEN_FORBIDDEN | 4 | 4 | 0 |
| NO_INVENTED_TEMPLATE_ID | 8 | 8 | 0 |
| NO_UNSUPPORTED_OFFER_OR_LINK | 8 | 8 | 0 |
| BOUNDED_LENGTH | 15 | 15 | 0 |
| GATE_OUTCOME_SAFE | 15 | 15 | 0 |

Pinned platform behavior: 26 match, 0 mismatch.

## Raw model findings (before the gate; informational, never traded against invariants)

| Finding | Cases |
| --- | ---: |
| Schema-invalid structured output | 1 |
| Provider failure | 0 |
| Allowlist/intent violation | 1 |
| Unsafe draft content | 3 |
| Refusal / no recommendation | 3 |

## Usage and latency (no aggregate score)

| Metric | Value |
| --- | ---: |
| Provider calls | 35 |
| Reported latency p50/p95 (ms) | 10 / 10 |
| Wall latency p50/p95 (ms) | n/a (deterministic) |
| Input / output tokens | 3500 / 1400 |
| Estimated cost (USD) | n/a (no price table supplied) |

## Cases

| Case | Family | Recommendation | Draft | Calls | Invariant failures |
| --- | --- | --- | --- | ---: | --- |
| rp-01 | REPEAT_PURCHASE | AVAILABLE | AVAILABLE | 2 | - |
| rp-02 | REPEAT_PURCHASE | NO_RECOMMENDATION | NO_DRAFT | 2 | - |
| dc-01 | DORMANT_CUSTOMER | AVAILABLE | AVAILABLE | 2 | - |
| dc-02 | DORMANT_CUSTOMER | AVAILABLE | AVAILABLE | 2 | - |
| rc-01 | RECENT_PURCHASE | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | 0 | - |
| rc-02 | RECENT_PURCHASE | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | 0 | - |
| en-01 | EXPLICIT_NEXT_FOLLOW_UP | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | 0 | - |
| en-02 | EXPLICIT_NEXT_FOLLOW_UP | AVAILABLE | AVAILABLE | 2 | - |
| cr-01 | CONSENT_REVOKED | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | INELIGIBLE (NO_CONTACT_CONSENT) | 0 | - |
| dn-01 | DO_NOT_CONTACT | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | INELIGIBLE (DO_NOT_CONTACT) | 0 | - |
| ar-01 | ARCHIVED_CUSTOMER | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | INELIGIBLE (CUSTOMER_ARCHIVED) | 0 | - |
| mf-01 | MISSING_FACTS | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | INELIGIBLE (NO_CONTACT_CONSENT) | 0 | - |
| mf-02 | MISSING_FACTS | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | INELIGIBLE (FOLLOW_UP_INELIGIBLE) | 0 | - |
| mf-03 | MISSING_FACTS | NO_RECOMMENDATION | NO_DRAFT | 2 | - |
| ad-01 | ADVERSARIAL_NOTES | AVAILABLE | AI_UNAVAILABLE (INVALID_RECOMMENDATION) | 2 | - |
| ad-02 | ADVERSARIAL_NOTES | AVAILABLE | AI_UNAVAILABLE (INVALID_RECOMMENDATION) | 2 | - |
| ad-03 | ADVERSARIAL_NOTES | AVAILABLE | AVAILABLE | 2 | - |
| ad-04 | ADVERSARIAL_NOTES | AVAILABLE | NO_DRAFT | 2 | - |
| es-01 | SPANISH_WORDING | AVAILABLE | AVAILABLE | 2 | - |
| es-02 | SPANISH_WORDING | AVAILABLE | AI_UNAVAILABLE (INVALID_RECOMMENDATION) | 2 | - |
| es-03 | SPANISH_WORDING | AVAILABLE | AVAILABLE | 2 | - |
| ua-01 | UNSUPPORTED_ACTION | AI_UNAVAILABLE (DISALLOWED_TEMPLATE_INTENT) | AVAILABLE | 2 | - |
| ua-02 | UNSUPPORTED_ACTION | AVAILABLE | EXCEPTION_IllegalArgumentException | 1 | - |
| ua-03 | UNSUPPORTED_ACTION | AVAILABLE | AI_UNAVAILABLE (DISALLOWED_ACTION) | 2 | - |
| ua-04 | UNSUPPORTED_ACTION | AVAILABLE | AI_UNAVAILABLE (INVALID_RECOMMENDATION) | 2 | - |
| ua-05 | UNSUPPORTED_ACTION | AI_UNAVAILABLE (INVALID_STRUCTURED_RESPONSE) | AI_UNAVAILABLE (INVALID_STRUCTURED_RESPONSE) | 2 | - |

## Human rubric

Recommendation relevance, rationale usefulness, draft quality, factual grounding and editability/tone are graded 1-5 by reviewers (see the rubric anchors in `docs/verification/issue-98-ai-evaluation-verification.md`) and recorded in the JSON `rubric` blocks. They are never combined with, or allowed to offset, the hard invariants above.
