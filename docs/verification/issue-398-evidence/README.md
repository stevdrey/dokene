# Issue #398 evidence

Sanitized evidence for the [Issue #398 decision record](../issue-398-adversarial-pass-decision.md).

- `verdicts/<issue>.json`: the schema-version-1 verdict of each replay verifier, validated with `verification-verdict.mjs` against its candidate.
- `verifier-prompt-template.txt`: the prompt every replay verifier received, with its placeholders.
- `probes/<issue>/<hypothesis>/`: probe sources (`*.java.txt`, stored as text so no build or scanner treats them as code), negative-control patches, and redacted outputs (`*-output.txt`; the verifiers wrote some as `.log`, which the repository ignores).

The #315 verdict is missing: its scratch directory was cleared by a session restart after it validated. Local paths in the files are replaced with `<replay>` (the scratch directory) and `~`.

All probes used invented data and local services only. No live provider, message send, or production data was involved.
