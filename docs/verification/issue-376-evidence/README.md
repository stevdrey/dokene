# Issue #376 benchmark evidence

Evidence for the [Issue #376 decision record](../issue-376-benchmark-decision.md). Everything here is synthetic or derived from the benchmark runs; no secret, token, or personal data is stored.

| File | Content |
| --- | --- |
| `K1.patch`, `K2.patch`, `K3.patch` | The three candidates, each applied to base `88d7523` (trees `c8856365…`, `897e19fd…`, `d470bc0d…`) |
| `ground-truth.md` | The seeded defects, written before the first verifier ran |
| `verifier-prompt-template.txt` | The handoff every verifier received, with placeholders |
| `probes/*.java.txt` | Probe sources the verifiers wrote, stored as text so that no build or scanner treats them as code |
| `verdicts/*.json` | The six verdicts, one per candidate and arm |

## About `ground-truth.md`

The file is kept byte-identical to the copy sealed before the runs, so two of its lines describe the benchmark setup rather than this directory:

- `seed.py` was a local script that applied exactly the edits now stored as the three patches. It is not committed; the patches replace it.
- "Verifiers must not read this directory" applied to the benchmark verifiers while they ran, when the file lived in a scratch directory. It does not restrict readers of the repository.
