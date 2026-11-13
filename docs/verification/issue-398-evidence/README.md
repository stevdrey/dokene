# Issue #398 evidence

Sanitized evidence for the [Issue #398 decision record](../issue-398-adversarial-pass-decision.md).

- `verdicts/<issue>.json`: the schema-version-1 verdict of each replay verifier, validated with `verification-verdict.mjs` against its candidate.
- `verifier-prompt-template.txt`: the prompt every replay verifier received, with its placeholders.
- `probes/<issue>/<hypothesis>/`: probe sources (`*.java.txt`, stored as text so no build or scanner treats them as code), negative-control patches, and redacted outputs (`*-output.txt`; the verifiers wrote some as `.log`, which the repository ignores).

Paths in the verdicts point to the replay's scratch layout. `<replay>/runs/<n>/probes/<dir>/` and `runs/<n>/probes/<dir>/` are `probes/<n>/<dir>/` here, and a `.log` there is a `*-output.txt` here: for #311, `run.log` is `run-output.txt`; for #325, `output.log` is `negative-control-output.txt`. The #325 phase A test log (`C1-phaseA.log`) is not kept; check C1's summary carries its result.

The #315 verdict is missing: its scratch directory was cleared by a session restart after it validated. Local paths in the files are replaced with `<replay>` (the scratch directory) and `~`.

All probes used invented data and local services only. No live provider, message send, or production data was involved.
