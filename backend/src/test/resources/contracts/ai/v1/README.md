# AI draft evidence fixtures — v1

The evidence labels whose values the frontend formats for display ([ADR 0018](../../../../../../../docs/adr/0018-constrained-follow-up-message-draft-generation.md) `Label: Value`, issue 194), and the follow-up statuses a draft can cite.

Two consumers read `draft-evidence.json`, and both fail when it drifts:

- backend: `DraftEvidenceContractFixturesTest` checks that every listed label resolves, in `DraftGroundingContext.sourceForLabel`, to the purchase dates or follow-up status source with an exact match, that every advertised label resolving to those sources is listed, and that the statuses are `FollowUpStatus` values;
- frontend: `src/features/followups/__tests__/draftEvidence.test.ts` checks the tables in `utils/draftEvidence.ts` and `utils/aiLabels.ts`.

Change a label or alias in the backend together with this file and the frontend tables.
