# Issue #326 rendered template safety: verification record

Status: single PR, part of epic [#305](https://github.com/stevdrey/dokene/issues/305). The design is in the [ADR 0025](../adr/0025-whatsapp-template-registry-and-dispatch-policy.md) issue 326 addendum and [security invariant 31](../security/security-invariants.md).

This record is sanitized: it gives rules, outcomes and test names only. It contains no credential or reproduction beyond the automated tests in the repository. All data is synthetic, and no live provider was called.

## Environment

- **Run:** 2026-11-08, branch `326-security-apply-message-safety-checks-to-template-parameters`, based on `main` at `38deb90` (through #364). The analysis named a worktree at `a328848`; the code was rechecked on the base first and the finding still applied: `MessageSendGuards.check` validated only the body, and `ApprovalBindingAssembler.render` passed a `customer_name` value through `TemplateParameterValues`, which checks provider rules only. **Tested revision:** the candidate on top of `38deb90`; a commit cannot name its own hash, so the PR names it.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build (Temurin 26.0.2).
- **Database:** Testcontainers PostgreSQL through the existing integration suites, migrated by Flyway (no new migration).

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| Rejected values, for links, provider identifiers, amounts, percentages and offer words, including obfuscated spellings: refused at submit and approval with a closed code that quotes nothing, no preview at review | `RenderedContentSafetyTest.anUnsafeParameterValueIsRefusedOnEveryRenderingPath` |
| Safe values: reviewed and bound unchanged | `RenderedContentSafetyTest.aSafeRenderedMessageIsReviewedAndBoundUnchanged` |
| Rendered content: values that are safe alone but unsafe once the fixed text joins them are refused | `RenderedContentSafetyTest.valuesThatFormUnsafeContentOnlyOnceRenderedAreRefused` |
| Grounding: a purchase grounds an offer as it does for a body; the name never grounds itself | `RenderedContentSafetyTest.anOfferIsGroundedOnlyByAPurchaseAsInTheBodyNeverByTheNameItself` |
| Vocabulary follows the message locale | `RenderedContentSafetyTest.theRenderedContentUsesTheVocabularyOfTheMessageLocale` |
| Submit, review and approval over HTTP: refusals with no open-message slot taken, a `VIEWER` read stays `200`, and consent still refuses once the name is safe, then approval succeeds | `MessageApiIntegrationTest.anUnsafeCustomerNameIsRefusedAtSubmitReviewAndApprovalWhileASafeOneKeepsTheirControls` |
| Dispatch: refused with no provider call, attempt, transition or idempotency row | `OutboundDispatchIntegrationTest.anUnsafeParameterValueAtSendTimeMakesNoProviderCallAndWritesNothing`, `SendPolicyGateTest.renderedContentTheSafetyRulesRefuseNeverBecomesASendCommand` |
| Listed surnames: a real surname that is also an offer word passes after a given name, in review, approval and a greeting | `RenderedContentSafetyTest.aListedSurnameAfterAGivenNameIsNotAnOfferWord`, `RenderedContentSafetyTest.anEnglishSurnameIsListedForAnEnglishMessage`, `MessageApiIntegrationTest.aListedSurnameIsSubmittedAndApprovedWhileTheSameWordElsewhereIsNot` |
| The exemption is bounded: names that do not read as a person's, or that hold a quantity, a commercial or function word, a digit, a symbol, a link or another offer word, are refused, as are the same word elsewhere in a message and the name near a quantity in any form the validator reads. An invented name of the permitted shape remains possible and carries no number, link or other offer word | `RenderedContentSafetyTest.aListedSurnameOpensNoOtherContent`, `RenderedContentSafetyTest.onlyTheCustomersOwnFullNameIsExempted`, `RenderedContentSafetyTest.aQuantityOrCommercialWordNearTheNameKeepsItRefused`, `RenderedContentSafetyTest.noWrittenQuantityTheValidatorReadsCanPrecedeASurnameOrStandNearTheName` (every word in the validator's list), `RenderedContentSafetyTest.aDisguisedEarlierWordDoesNotQualifyTheName`, `RenderedContentSafetyTest.paddingTheValidatorIgnoresDoesNotMoveANumberOutOfReach` |
| The fixed-text rules did not change, so approved template evidence stays trusted | `FixedTextRulesVersionTest` (unchanged) |

## Results

| Command | Kind | Result |
| --- | --- | --- |
| `./gradlew test --tests '*RenderedContentSafetyTest' --tests '*ApprovalBindingAssemblerTest' --tests '*SendPolicyGateTest' --tests '*DraftSafetyValidatorTest' --tests '*FixedTextRulesVersionTest' --tests '*OutboundMessageCommandServiceValidationTest' --tests '*ModuleDependencyArchitectureTest'` | automated | passed after a test-stub fix; the one earlier failure was in the test's own Mockito re-stubbing |
| `./gradlew test --tests '*RenderedContentSafetyTest' --tests '*MessageApiIntegrationTest' --tests '*OutboundDispatchIntegrationTest' --tests '*EnglishTemplateSendJourneyIntegrationTest' --tests '*ApprovedSendJourneyIntegrationTest'` | automated | BUILD SUCCESSFUL |
| Negative control (local, not committed): the `checkRendered` call removed from `render` | automated | 15 of 29 selected tests failed, including the HTTP and dispatch tests above |
| Negative control (local, not committed), first exemption: the digit guards removed from `AmbiguousSurnames` | automated | 1 of 30 selected tests failed (`onlyTheCustomersOwnFullNameIsExempted`) |
| `./gradlew check` (backend), first commit `9ad50ef` | automated | BUILD SUCCESSFUL: 2573 tests, 0 failures, 0 errors, 0 skipped |
| `./gradlew check` (backend), first exemption | automated | BUILD SUCCESSFUL: 2590 tests, 0 failures; independent verification then failed it for being too broad, and the exemption was narrowed |
| `./gradlew check` (backend), narrowed exemption | automated | BUILD SUCCESSFUL: 2609 tests, 0 failures; independent verification then failed it because its quantity list was a partial copy of the validator's |
| `./gradlew check` (backend), validator's own quantities, commit `69de707` | automated | BUILD SUCCESSFUL: 2617 tests, 0 failures; a later independent round failed it because the reach was measured on the raw text |
| `./gradlew check` (backend), reach measured on the folded text | automated | BUILD SUCCESSFUL: 2620 tests, 0 failures, 0 errors, 0 skipped |

Notes:

- One existing fixture in `MessageApiIntegrationTest` used fixed text with an offer word, which a refresh would never admit; it now uses neutral text.
- The obfuscated zero-width spelling is refused by the provider parameter rule, before this check, so the negative control does not fail on that case.

Not run: a live provider send, because the check runs before any provider call and the provider adapter did not change. No frontend change was made (`BODY_REJECTED` already has its label), so no frontend checks were run.
