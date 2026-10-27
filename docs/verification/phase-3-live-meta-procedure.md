# Phase 3 opt-in live Meta procedure

This is how to run one bounded, explicitly authorized live WhatsApp journey against Meta's Cloud API. It is **opt-in**: nothing in the build, the test suites or CI ever calls Meta. Deterministic coverage of the same path lives in `ApprovedSendJourneyIntegrationTest` (issue 140) and needs no credentials. A live run can only add evidence that the real provider behaves as the fixtures say. Offline fixtures can never stand in for a live-provider claim.

The procedure was written for issue 140 and first used on `main` `f4cb0ec`. The results are in [phase-3-release-certification.md](phase-3-release-certification.md).

## Preconditions

All of these must hold. If one does not, the live leg is **BLOCKED**, not PASS.

1. **Non-production Meta assets.**
   - A Meta developer app in development mode, with a WhatsApp Business test account.
   - Its test sender number, and an approved template in that account.
   - Never a production business account or a customer's number.
2. **Recipient consent.**
   - The only recipient is a person who has explicitly agreed to receive the test messages, and who is listed as a test recipient in the Meta app.
   - Their number is recorded in Dokene with WhatsApp consent `GRANTED` and no do-not-contact flag.
3. **Explicit authorization for this run.**
   - The person running the procedure has been told "yes, one send" for this specific run.
   - An approval for an earlier run never carries over.
4. **Short-lived credentials kept out of the repository.**
   - The Graph access token and the app secret are referenced by `secretRef` from the local secret source (`DOKENE_INTEGRATION_SECRETS_SOURCE=local`), from a file outside the repository.
   - A Meta test token expires after about 24 hours. An expired token shows as `PROVIDER_UNAVAILABLE` on template refresh (Graph `190`/`463`), so refresh it before the run.
5. **Callback reachability, for the callback leg only.**
   - Meta must reach the webhook over public HTTPS, for example through a temporary tunnel in front of the local gateway.
   - The app's Callback URL must point at it, with the `messages` field subscribed.
   - **An app in development mode only delivers the dashboard "Test" webhooks, not real status callbacks for sent messages.** The real `delivered`/`read` callback leg therefore needs the app published. Until then it stays BLOCKED.

## Steps

1. **Pin the build.** Record the commit, then build and start the stack from it with the live override. The override carries the local secret source, the secret references and `DOKENE_MESSAGING_OUTBOUND_ENABLED=false`.
2. **Prove the sender.** Configure the integration through `PUT /api/integrations/whatsapp`, then prove ownership with `POST /api/integrations/whatsapp/ownership-verifications`. `webhookVerified` must be `true`.
3. **Map and verify the template.**
   - `PUT /api/templates/GENERAL_FOLLOW_UP`, then `POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh`.
   - The mapping must answer `APPROVED` and `sendable: true` from Graph itself.
4. **Make the recipient due.** Set the follow-up policy with an explicit next date of today and confirm `follow-up-eligibility` is eligible.
   - Respect `dokene.messaging.min-contact-interval` (24h). If the recipient was messaged less than 24 hours earlier, wait.
   - Lowering the interval is allowed only on the QA stack, and only with the recipient owner's explicit authorization for that run (`DOKENE_MESSAGING_MIN_CONTACT_INTERVAL` through the live override). Record it in the report, and restore `24h` in step 7 together with the switches.
5. **Open the send window, and only for this run.**
   - Start the backend with the global switch on (`DOKENE_MESSAGING_OUTBOUND_ENABLED=true`).
   - Turn the tenant switch on with `PUT /api/integrations/whatsapp/outbound`.
6. **One send through the normal API**, with fixed idempotency keys so a rerun can never create a second message or attempt:
   - submit (`POST /api/customers/{id}/messages`, `If-Match` = policy version);
   - approve with the preview revision (`templateRevision` from `template.revision`);
   - send (`POST /api/messages/{id}/sends`).
   - Record the outcome: `status`, `outcomeUnknown`, `failureCategory` and the number of attempts.
   - **Never resend automatically.** An uncertain outcome is investigated, not retried.
7. **Close the window immediately.**
   - Tenant switch off, then restart the backend with the global switch off.
   - Confirm both through `GET /api/integrations/whatsapp` and the startup property.
   - If the contact interval was lowered for the run, start the backend again with the default `24h`.
8. **Callback leg.** Where preconditions allow, confirm in the webhook inbox that the signed callback arrived and was `APPLIED`. Confirm on the message detail and timeline that the status moved to `DELIVERED` or `READ`.
9. **Ask the recipient** to confirm receipt in their own words. Record only "confirmed" or "not confirmed".
10. **Stop the tunnel** and revert the Callback URL if it was changed for this run.

## Stop and rollback

- **Kill switches:** either switch stops the next send at once. The tenant switch takes effect on the next request; the global switch takes effect on the next backend start. Both are off by default and must be off when the run ends.
- **Uncertain or failed outcome:** stop. Do not retry, do not send to anyone else, and record the outcome as found.
- **Credential exposure:** rotate the Meta token and the app secret, and record the event (never the value).

## Evidence rules

- Record the commit, the date, the Graph API version (`v23.0` by default) and which preconditions held.
- Record results per leg as PASS, FAIL or BLOCKED.
- Never record a phone number, token, app secret, WABA or phone number id, provider message id, template body text or raw provider payload in the repository. Redact or summarize instead.
- A scoped live run proves the one journey it ran. It does not prove that no other defect exists.
