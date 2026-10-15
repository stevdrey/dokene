# WhatsApp Graph API send fixtures

Synthetic Meta Cloud API answers to `POST /{phone-number-id}/messages` ([ADR 0027](../../../../../../docs/adr/0027-meta-whatsapp-send-adapter.md)), replayed by `GraphApiMessagingProviderTest` from a loopback server. Each entry is `{ "status": <HTTP status>, "body": <JSON body> }`; the shapes follow Meta's published success and error payloads.

Rules:

- Data is synthetic only: `+506000000xx` numbers, `wamid.SYNTHETIC-…` message ids, `SYNTHETIC-NOT-A-REAL-TOKEN` credentials and `SYNTHETIC-TRACE` trace ids. Never copy a real provider response here.
- `tokenExpiredWithSecrets` deliberately echoes a token, a phone number and message text, so the redaction test can prove none of them crosses the adapter.
- These fixtures describe provider behaviour, not Dokene's HTTP contract; changing them does not version `contracts/messaging`.
