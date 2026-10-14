---
name: github-issue-executive-summary
description: Write and optionally post a non-technical executive summary, in English Markdown, as a comment on a GitHub Issue after its implementing PR is done. Use when asked to summarize delivered work, a milestone, or an outcome for stakeholders on an Issue.
---

# GitHub Issue Executive Summary

## Purpose

Produce a clear, high-level, non-technical summary of what a finished piece of work delivered, written for the people who opened or follow the Issue (product owners, managers, stakeholders), not for code reviewers.

## Core Principles

1. **Issue-centric.** The summary goes on the **GitHub Issue**, not on the Pull Request. Post with:
   ```bash
   gh issue comment <issue-number> --body-file <summary.md>
   ```
   Write the Markdown to a scratch file and use `--body-file` so backticks and quotes survive. Posting is public: post only when the user asked for it. To correct a posted summary, edit it with `gh api -X PATCH repos/<owner>/<repo>/issues/comments/<id>` instead of adding a second comment.
2. **Non-technical audience.** Replace class names, file paths, commit hashes, and internal mechanics with plain concepts. Pick one everyday analogy (a claim ticket, a reception desk, a dashboard) and carry it through every section.
3. **Always in English**, even if the request was in another language.
4. **Facts, never guesses.** Every number comes from the repository, the PR, or a test run.
5. **Follow the house style.** If the repository has a project skill or earlier summaries on sibling Issues, mirror their structure and tone.

## Gathering Facts First

1. The Issue (`gh issue view <n>`) and the PR that implements it (`gh pr view`, `git diff main...HEAD --stat`).
2. The most recent summary on a closed sibling Issue (`gh api repos/<owner>/<repo>/issues/<n>/comments`), as the structural example.
3. Real test counts from the final commit: run the suite and read totals from the test reports, and count the tests the PR added. State which modules or suites were **not** re-run.
4. Review rounds on the PR, automated and human, and whether every point was addressed.
5. Documentation added or updated (decision records, architecture guides, README examples).

## Template

Keep this section order.

```markdown
## 🌟 Executive Summary: <Title> (Issue #N)

### 🎯 What is this issue about?
<2-3 sentences: what the product is, the problem, and one everyday analogy.>

---

### 💡 Why is this important?
- **<Value>**: <Concrete benefit in plain language.>

---

### 🧩 What was delivered?
- 🎟️ **<Capability>**: <Plain-language description.>
- 🔁 **Nothing Changes If You Don't Use It**: <Backward-compatibility statement, when true.>
- 🧪 **Verification**: <Exact passing test totals of the final commit, how many are new, what they prove, and which suites were not re-run.>
- 📚 **Documentation**: <Decision records, guides, and examples added or updated.>
- 🔍 **Review Rounds**: <How many points were raised, that all were addressed, and the kind of fix.>

---

### ⚠️ Known Limits
- **<Limit>**: <Honest statement of what is not covered, intentional trade-offs, and that no real product uses it yet when that is true.>

---

### ✅ Key Takeaways & Business Value
- **<Value>**: <Outcome, boundary preserved, or capability this unlocks next.>

📎 Implemented in PR #<N>.
```

Omit "Review Rounds" only when there were none. Always keep "Known Limits": an empty one means the facts were not gathered.

## Checklist Before Posting

- [ ] Entirely in English, professional and accessible, with one consistent analogy?
- [ ] No class names, file paths, or commit hashes in the body?
- [ ] Test totals are real, taken from the final commit, with un-run suites stated?
- [ ] Known Limits and the `📎 Implemented in PR #N` line are present?
- [ ] The target is a GitHub Issue (`gh issue comment`), and the user asked to post?
