import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

const SCRIPT = new URL('./verification-verdict.mjs', import.meta.url).pathname;

function command(cmd, args, cwd) {
  return spawnSync(cmd, args, { cwd, encoding: 'utf8', timeout: 15_000 });
}

function git(cwd, ...args) {
  const result = command('git', args, cwd);
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function repository(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'dokene-verdict-'));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  git(cwd, 'init', '-q', '-b', 'main');
  git(cwd, 'config', 'user.name', 'Test Agent');
  git(cwd, 'config', 'user.email', 'agent@example.invalid');
  writeFileSync(join(cwd, 'README.md'), 'baseline\n');
  git(cwd, 'add', '.');
  git(cwd, 'commit', '-qm', 'baseline');
  git(cwd, 'update-ref', 'refs/remotes/origin/main', 'HEAD');
  git(cwd, 'switch', '-qc', 'feature/issue-1');
  writeFileSync(join(cwd, 'feature.txt'), 'implementation\n');
  git(cwd, 'add', '.');
  git(cwd, 'commit', '-qm', 'implement');
  return cwd;
}

function identity(cwd, env = {}, options = []) {
  const result = spawnSync(process.execPath, [SCRIPT, '--identity', ...options], {
    cwd, encoding: 'utf8', timeout: 15_000, env: { ...process.env, ...env },
  });
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

// Verdicts live outside the worktree, as the skill requires, so writing one never changes the candidate.
function check(t, cwd, verdict, options = []) {
  const dir = mkdtempSync(join(tmpdir(), 'dokene-verdict-file-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const file = join(dir, 'verdict.json');
  writeFileSync(file, typeof verdict === 'string' ? verdict : JSON.stringify(verdict));
  return command(process.execPath, [SCRIPT, '--verdict', file, ...options], cwd);
}

function passingVerdict(candidate) {
  return {
    schemaVersion: 1,
    issue: 1,
    candidate,
    verifier: { independent: true, kind: 'subagent' },
    criteria: [
      { id: 'AC1', text: 'Feature file is created', result: 'pass', evidence: ['C1'] },
      { id: 'AC2', text: 'Empty input is rejected', result: 'pass', evidence: ['C1', 'C2'] },
    ],
    findings: [
      { id: 'F1', severity: 'optional', status: 'open', summary: 'Naming could be clearer', evidence: 'feature.txt:1' },
    ],
    checks: [
      { id: 'C1', kind: 'automated', required: true, command: 'node --test', result: 'passed', environment: 'local' },
      { id: 'C2', kind: 'manual', required: false, command: 'submit an empty form', result: 'passed', environment: 'local dev stack' },
    ],
    limitations: [],
    remainingActions: [],
    verdict: 'pass',
  };
}

test('identity is the merge base, the commit, and the committed tree for a clean worktree', t => {
  const cwd = repository(t);
  const current = identity(cwd);
  assert.equal(current.base, git(cwd, 'rev-parse', 'main'));
  assert.equal(current.head, git(cwd, 'rev-parse', 'HEAD'));
  assert.equal(current.tree, git(cwd, 'rev-parse', 'HEAD^{tree}'));
});

test('uncommitted and untracked changes each change the identity, without touching the index', t => {
  const cwd = repository(t);
  const clean = identity(cwd).tree;
  writeFileSync(join(cwd, 'feature.txt'), 'edited\n');
  const edited = identity(cwd).tree;
  assert.notEqual(edited, clean);

  writeFileSync(join(cwd, 'new file é.txt'), 'new\n');
  const withUntracked = identity(cwd).tree;
  assert.notEqual(withUntracked, edited);

  writeFileSync(join(cwd, 'new file é.txt'), 'changed\n');
  assert.notEqual(identity(cwd).tree, withUntracked);
  assert.equal(git(cwd, 'status', '--porcelain'), 'M feature.txt\n?? "new file \\303\\251.txt"');
});

test('committing the verified content keeps the candidate identity', t => {
  const cwd = repository(t);
  writeFileSync(join(cwd, 'feature.txt'), 'verified\n');
  writeFileSync(join(cwd, 'added.txt'), 'added\n');
  const verified = identity(cwd);
  const verdict = passingVerdict(verified);

  git(cwd, 'add', '.');
  git(cwd, 'commit', '-qm', 'publish verified content');
  const committed = identity(cwd);
  assert.notEqual(committed.head, verified.head);
  assert.equal(committed.tree, verified.tree);
  assert.equal(check(t, cwd, verdict).status, 0);
});

test('identity ignores diff configuration and hashes symlinks and nested repositories by reference', t => {
  const cwd = repository(t);
  writeFileSync(join(cwd, 'feature.txt'), 'edited\n');
  symlinkSync('missing-target', join(cwd, 'dangling'));
  mkdirSync(join(cwd, 'dir'));
  writeFileSync(join(cwd, 'dir', 'inside.txt'), 'inside\n');
  symlinkSync('dir', join(cwd, 'dir-link'));
  mkdirSync(join(cwd, 'nested'));
  git(join(cwd, 'nested'), 'init', '-q');
  writeFileSync(join(cwd, 'nested', 'inner.txt'), 'inner\n');
  git(join(cwd, 'nested'), 'add', '.');
  git(join(cwd, 'nested'), '-c', 'user.name=Test', '-c', 'user.email=t@example.invalid', 'commit', '-qm', 'inner');
  const plain = identity(cwd).tree;
  const configured = identity(cwd, {
    GIT_CONFIG_COUNT: '3',
    GIT_CONFIG_KEY_0: 'diff.noprefix', GIT_CONFIG_VALUE_0: 'true',
    GIT_CONFIG_KEY_1: 'diff.context', GIT_CONFIG_VALUE_1: '1',
    GIT_CONFIG_KEY_2: 'diff.mnemonicPrefix', GIT_CONFIG_VALUE_2: 'true',
  }).tree;
  assert.equal(configured, plain);

  rmSync(join(cwd, 'dangling'));
  symlinkSync('other-target', join(cwd, 'dangling'));
  assert.notEqual(identity(cwd).tree, plain);
});

test('publication mode checks the commit, not a verified change left out of it', t => {
  const cwd = repository(t);
  writeFileSync(join(cwd, 'feature.txt'), 'corrected\n');
  writeFileSync(join(cwd, 'tests.txt'), 'regression test\n');
  const verdict = passingVerdict(identity(cwd));
  assert.equal(check(t, cwd, verdict).status, 0);

  const unstaged = check(t, cwd, verdict, ['--commit', 'HEAD']);
  assert.equal(unstaged.status, 2);
  assert.match(unstaged.stderr, /STALE: verdict is for .* but commit HEAD is tree /);

  git(cwd, 'add', 'feature.txt');
  git(cwd, 'commit', '-qm', 'partial commit');
  assert.equal(check(t, cwd, verdict, ['--commit', 'HEAD']).status, 2);

  git(cwd, 'add', 'tests.txt');
  git(cwd, 'commit', '-qm', 'rest of the verified change');
  const published = check(t, cwd, verdict, ['--commit', 'HEAD']);
  assert.equal(published.status, 0, published.stderr);
  assert.match(published.stdout, /PASS: Issue #1/);
});

test('a stacked branch is verified and published against the base carried through the handoff', t => {
  const cwd = repository(t);
  git(cwd, 'switch', '-qc', 'feature/stacked');
  writeFileSync(join(cwd, 'stacked.txt'), 'stacked change\n');
  const parent = ['--base', 'feature/issue-1'];
  const verdict = passingVerdict(identity(cwd, {}, parent));
  assert.equal(verdict.candidate.base, git(cwd, 'rev-parse', 'feature/issue-1'));

  const withoutBase = check(t, cwd, verdict);
  assert.equal(withoutBase.status, 2);
  assert.match(withoutBase.stderr, /STALE/);
  assert.equal(check(t, cwd, verdict, parent).status, 0);

  git(cwd, 'add', '.');
  git(cwd, 'commit', '-qm', 'stacked');
  const published = check(t, cwd, verdict, [...parent, '--commit', 'HEAD']);
  assert.equal(published.status, 0, published.stderr);
});

test('publication mode rejects a revision that is not a commit', t => {
  const cwd = repository(t);
  const result = check(t, cwd, passingVerdict(identity(cwd)), ['--commit', 'no-such-ref']);
  assert.equal(result.status, 2);
  assert.match(result.stderr, /INVALID: git rev-parse failed/);
});

test('a verdict about another candidate is stale even when it would pass', t => {
  const cwd = repository(t);
  const verdict = passingVerdict(identity(cwd));
  writeFileSync(join(cwd, 'feature.txt'), 'changed after verification\n');
  const result = check(t, cwd, verdict);
  assert.equal(result.status, 2);
  assert.match(result.stderr, /STALE: verdict is for tree [0-9a-f]{12}/);
});

test('scenario: a candidate meeting every criterion passes', t => {
  const cwd = repository(t);
  const result = check(t, cwd, passingVerdict(identity(cwd)));
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /PASS: Issue #1 candidate .*: 2 criteria verified/);
});

test('scenario: a missed requirement and an edge-case defect fail', t => {
  const cwd = repository(t);
  const verdict = passingVerdict(identity(cwd));
  verdict.criteria[1].result = 'fail';
  verdict.findings.push({
    id: 'F2', severity: 'blocker', status: 'open', criteria: ['AC2'],
    summary: 'Whitespace-only input is accepted',
    evidence: "printf '  ' | node app.js exits 0 and stores an empty record",
  });
  verdict.verdict = 'fail';

  const result = check(t, cwd, verdict);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /FAIL: Issue #1/);
  assert.match(result.stderr, /criterion AC2 failed/);
  assert.match(result.stderr, /blocker finding F2 is open: Whitespace-only input is accepted/);
});

test('a pass declared over an open blocker finding is rejected', t => {
  const cwd = repository(t);
  const verdict = passingVerdict(identity(cwd));
  verdict.findings.push({ id: 'F2', severity: 'blocker', status: 'open', summary: 'Crash on null', evidence: 'stack trace' });

  const result = check(t, cwd, verdict);
  assert.equal(result.status, 2);
  assert.match(result.stderr, /INCONSISTENT: declared pass, but the evidence supports fail/);
});

test('a failed required check fails the verdict', t => {
  const cwd = repository(t);
  const verdict = passingVerdict(identity(cwd));
  verdict.checks[0].result = 'failed';
  verdict.criteria = [{ id: 'AC1', text: 'Feature file is created', result: 'pass', evidence: ['C2'] }];
  verdict.verdict = 'fail';

  const result = check(t, cwd, verdict);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /required check C1 failed/);
});

test('scenario: an unavailable test environment blocks the verdict', t => {
  const cwd = repository(t);
  const verdict = passingVerdict(identity(cwd));
  verdict.checks.push({
    id: 'C3', kind: 'live', required: true, command: './gradlew integrationTest', result: 'not-run',
    environment: 'Docker unavailable in the sandbox',
  });
  verdict.criteria.push({ id: 'AC3', text: 'Data survives a restart', result: 'blocked', evidence: [] });
  verdict.limitations.push('Docker is unavailable, so persistence was not exercised.');
  verdict.remainingActions.push('Run ./gradlew integrationTest where Docker is available.');
  verdict.verdict = 'blocked';

  const result = check(t, cwd, verdict);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /BLOCKED: Issue #1/);
  assert.match(result.stderr, /criterion AC3 could not be verified/);
  assert.match(result.stderr, /required check C3 was not run/);
});

test('a verdict without an independent verifier cannot pass', t => {
  const cwd = repository(t);
  const verdict = passingVerdict(identity(cwd));
  verdict.verifier = { independent: false, kind: 'self-review' };
  verdict.verdict = 'blocked';

  const result = check(t, cwd, verdict);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /the verifier was not an independent subagent/);
});

test('a criterion passing without evidence or on unknown evidence is blocked', t => {
  const cwd = repository(t);
  const verdict = passingVerdict(identity(cwd));
  verdict.criteria[0].evidence = [];
  verdict.criteria[1].evidence = ['C9'];
  verdict.verdict = 'blocked';

  const result = check(t, cwd, verdict);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /criterion AC1 passes without evidence/);
  assert.match(result.stderr, /criterion AC2 cites unknown evidence C9/);
});

test('scenario: a corrected candidate needs a fresh verdict, reusing only justified evidence', t => {
  const cwd = repository(t);
  const original = identity(cwd);
  const failed = passingVerdict(original);
  failed.criteria[1].result = 'fail';
  failed.verdict = 'fail';
  assert.equal(check(t, cwd, failed).status, 1);

  // The fix makes a new candidate: the old verdict no longer describes the worktree.
  writeFileSync(join(cwd, 'feature.txt'), 'implementation with validation\n');
  const stale = check(t, cwd, failed);
  assert.equal(stale.status, 2);
  assert.match(stale.stderr, /STALE: verdict is for .* but the worktree is tree /);

  git(cwd, 'commit', '-qam', 'fix validation');
  const corrected = identity(cwd);
  assert.notEqual(corrected.head, original.head);

  // Re-verification may not carry the old candidate's test run forward unexplained.
  const fresh = passingVerdict(corrected);
  fresh.checks[1].candidate = original;
  fresh.verdict = 'blocked';
  const unjustified = check(t, cwd, fresh);
  assert.equal(unjustified.status, 1);
  assert.match(unjustified.stderr, /criterion AC2 cites stale evidence C2/);

  fresh.checks[1].reusedBecause = 'The form and its handler are unchanged between the two candidates.';
  fresh.verdict = 'pass';
  const reverified = check(t, cwd, fresh);
  assert.equal(reverified.status, 0, reverified.stderr);
  assert.match(reverified.stdout, /PASS: Issue #1/);
});

test('malformed or incomplete verdicts are invalid', t => {
  const cwd = repository(t);
  const malformed = check(t, cwd, '{not json');
  assert.equal(malformed.status, 2);
  assert.match(malformed.stderr, /cannot read verdict/);

  const verdict = passingVerdict(identity(cwd));
  delete verdict.limitations;
  verdict.findings[0].evidence = '';
  verdict.checks[0].kind = 'imagined';
  const incomplete = check(t, cwd, verdict);
  assert.equal(incomplete.status, 2);
  assert.match(incomplete.stderr, /limitations must be an array/);

  delete verdict.checks[0].kind;
  verdict.limitations = [];
  const invalid = check(t, cwd, verdict);
  assert.equal(invalid.status, 2);
  assert.match(invalid.stderr, /findings\[0\]\.evidence must give a reproduction/);
  assert.match(invalid.stderr, /checks\[0\]\.kind must be automated, manual, simulated or live/);

  const unmapped = passingVerdict(identity(cwd));
  unmapped.findings[0].criteria = ['AC9'];
  const unknownCriterion = check(t, cwd, unmapped);
  assert.equal(unknownCriterion.status, 2);
  assert.match(unknownCriterion.stderr, /findings\[0\]\.criteria must list ids of mapped criteria/);

  const withNull = passingVerdict(identity(cwd));
  withNull.criteria.push(null);
  withNull.checks.push(null);
  const nulls = check(t, cwd, withNull);
  assert.equal(nulls.status, 2);
  assert.match(nulls.stderr, /criteria\[2\] must be an object/);
  assert.match(nulls.stderr, /checks\[2\] must be an object/);
});
