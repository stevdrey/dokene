Found during #118 / #119 (REG-07, live OpenAI journey). Tested SHA `2e0a2549c43855b783cd8d0e318bc8c8fe662b4d`. Origin: **unknown / pre-existing in Phase 2 AI work (#94–#97)**; no comparison build was run, so it is not labelled a confirmed regression.

**Severity:** Low–Medium (UX consistency; operator-facing text in the wrong language).

**Environment:** Linux, JDK 26.0.2, Chrome (in-app browser) at 1280x800, backend `DOKENE_AI_PROVIDER=openai`, model `gpt-6-luna`, real api.openai.com (established TLS connection from the backend process to 172.66.0.243:443 verified with `ss -tnp`). Synthetic data only.

**Preconditions:** workspace "QA Café Norte" (OPERATOR `testoperator`); customer "Laura Pérez QA" with granted WhatsApp consent, purchases "Harina especial 5 kg y esencia de vainilla" (2026-09-05) and "Kit de moldes de repostería" (2026-07-10) → OVERDUE in the Seguimientos workbench.

**Steps**
1. Log in as `testoperator`, open **Seguimientos**, select the overdue customer.
2. Click **Obtener recomendación**.
3. Read the "POR QUÉ LO SUGIERE LA IA" block, then click **Generar borrador**.

**Expected:** every operator-visible AI text is in the product language (the UI is Spanish; ADR 0018 requires drafts in `es-419`). The rationale should be Spanish too, or the contract should document the language.

**Actual:** the whole UI and the *draft* are Spanish, but the rationale is English:

> "The customer is eligible and overdue; their flour and vanilla purchase was 32 days ago, making a replenishment follow-up timely."  (confidence 91 %)

The draft (Spanish, grounded, 183 chars): "Hola, Laura. Queríamos saber cómo te fue con la harina especial y la esencia de vainilla que compraste. Si necesitas reponerlos, estamos aquí para ayudarte. ¡Saludos de QA Café Norte!"

ADR 0015 only says the rationale is "human-readable, bounded (500 chars)"; no language is specified, so the contract is silent/inconsistent with ADR 0018.

**Reproducibility:** 1/1 live recommendation (not repeated to save budget; to be re-measured in #120/#124).

**Evidence:** UI text transcript above; audit rows `AI_INVOCATION_OUTCOME` (NEXT_BEST_ACTION, MESSAGE_DRAFT, GENERATED, SUCCESS) for correlation ids de46b815-… and 661b4bd8-…. Business state before/after the AI calls was identical (customer/policy/consent versions, queue).

**Security/privacy impact:** none.

**Suggested direction:** instruct the model (system prompt/schema) to write the rationale in the tenant locale, or document it; add to the evaluation harness an operator-language check. Related: #94–#97, #98.
