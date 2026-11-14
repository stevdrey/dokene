import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

const SCRIPT = new URL('./issue-loop.mjs', import.meta.url).pathname;
const VALIDATOR = new URL('./verification-verdict.mjs', import.meta.url).pathname;

function command(cmd, args, cwd) {
  return spawnSync(cmd, args, { cwd, encoding: 'utf8', timeout: 30_000 });
}

function git(cwd, ...args) {
  const result = command('git', args, cwd);
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function repository(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'dokene-loop-'));
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
  return cwd;
}

function identity(cwd) {
  const result = command(process.execPath, [VALIDATOR, '--identity'], cwd);
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

function verdict(candidate, result = 'pass', overrides = {}) {
  const criterion = { pass: 'pass', fail: 'fail', blocked: 'blocked' }[result];
  return {
    schemaVersion: 1,
    issue: 1,
    candidate,
    verifier: { independent: true, kind: 'subagent' },
    criteria: [{ id: 'AC1', text: 'Feature file is created', result: criterion, evidence: ['C1'] }],
    findings: [],
    checks: [{ id: 'C1', kind: 'automated', required: true, command: 'node --test', result: 'passed', environment: 'local', candidate }],
    limitations: result === 'blocked' ? ['test environment unavailable'] : [],
    remainingActions: [],
    verdict: result,
    ...overrides,
  };
}

/** The loop state and its verdicts live outside the worktree, as the skill requires, so they never change the candidate. */
function loop(t, cwd) {
  const dir = mkdtempSync(join(tmpdir(), 'dokene-loop-state-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const state = { schemaVersion: 1, issue: 1, questions: [], rounds: [] };
  return {
    state,
    addRound(content) {
      const name = `verdict-${state.rounds.length + 1}.json`;
      writeFileSync(join(dir, name), typeof content === 'string' ? content : JSON.stringify(content));
      state.rounds.push({ verdict: name });
    },
    next(options = []) {
      const file = join(dir, 'state.json');
      writeFileSync(file, JSON.stringify(state));
      const result = command(process.execPath, [SCRIPT, '--state', file, ...options], cwd);
      const decision = result.status === 2 ? undefined : JSON.parse(result.stdout);
      return { status: result.status, decision, stderr: result.stderr };
    },
    write(raw) {
      writeFileSync(join(dir, 'state.json'), raw);
      return command(process.execPath, [SCRIPT, '--state', join(dir, 'state.json')], cwd);
    },
  };
}

function expectAction(outcome, action, status) {
  assert.equal(outcome.status, status, outcome.stderr);
  assert.equal(outcome.decision.action, action, outcome.decision.reason);
}

test('scenario: success — a worktree pass is committed, and a pass for that commit allows publication', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  expectAction(run.next(), 'verify', 1);

  run.addRound(verdict(identity(cwd)));
  expectAction(run.next(), 'commit', 1);

  git(cwd, 'add', '.');
  git(cwd, 'commit', '-qm', 'implement');
  const published = run.next(['--commit', 'HEAD']);
  expectAction(published, 'publish', 0);
  assert.match(published.decision.reason, /checked against commit HEAD/);
});

test('scenario: verified content left out of the commit must be committed, not published', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd)));
  expectAction(run.next(['--commit', 'HEAD']), 'commit', 1);
});

test('scenario: clarification — an open material question blocks verification and publication until answered', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.state.questions.push(
    { id: 'Q1', text: 'Which base branch does the PR target?', material: true, status: 'open' },
    { id: 'Q2', text: 'Preferred wording of the log line?', material: false, status: 'open' },
  );
  run.addRound(verdict(identity(cwd)));
  const asked = run.next();
  expectAction(asked, 'ask', 1);
  assert.match(asked.decision.reason, /Q1/);
  assert.doesNotMatch(asked.decision.reason, /Q2/);

  run.state.questions[0].status = 'answered';
  run.state.questions[0].answeredAfterRound = 1;
  const answered = run.next();
  expectAction(answered, 'verify', 1);
  assert.match(answered.decision.reason, /Q1 were answered after round 1/);

  run.addRound(verdict(identity(cwd)));
  expectAction(run.next(), 'commit', 1);
});

test('scenario: fix and re-review — a failed candidate is fixed and freshly verified', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd), 'fail'));
  expectAction(run.next(), 'fix', 1);

  writeFileSync(join(cwd, 'feature.txt'), 'corrected\n');
  expectAction(run.next(), 'verify', 1);

  run.addRound(verdict(identity(cwd)));
  expectAction(run.next(), 'commit', 1);
});

test('scenario: stale verdict — an edit after a pass requires a fresh verification', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd)));
  writeFileSync(join(cwd, 'feature.txt'), 'edited after the pass\n');
  const stale = run.next();
  expectAction(stale, 'verify', 1);
  assert.match(stale.decision.reason, /STALE/);
});

test('scenario: blocked — a blocked verdict stops the loop', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd), 'blocked'));
  expectAction(run.next(), 'stop', 3);
});

test('scenario: blocked — a verdict without an independent verifier stops instead of self-certifying', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd), 'blocked', { verifier: { independent: false, kind: 'self' } }));
  expectAction(run.next(), 'stop', 3);

  const declaredPass = loop(t, cwd);
  declaredPass.addRound(verdict(identity(cwd), 'pass', { verifier: { independent: false, kind: 'self' } }));
  const rejected = declaredPass.next();
  expectAction(rejected, 'stop', 3);
  assert.match(rejected.decision.reason, /INCONSISTENT/);
});

test('re-verifying an unchanged failed candidate stops instead of looping', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  const candidate = identity(cwd);
  run.addRound(verdict(candidate, 'fail'));
  run.addRound(verdict(candidate, 'pass'));
  const stopped = run.next();
  expectAction(stopped, 'stop', 3);
  assert.match(stopped.decision.reason, /same candidate as failed round 1/);
});

test('a blocked candidate may be re-verified once the missing capability is obtained', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  const candidate = identity(cwd);
  run.addRound(verdict(candidate, 'blocked'));
  run.addRound(verdict(candidate, 'pass'));
  expectAction(run.next(), 'commit', 1);
});

test('a passing verdict for another Issue cannot publish', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd), 'pass', { issue: 2 }));
  const stopped = run.next();
  expectAction(stopped, 'stop', 3);
  assert.match(stopped.decision.reason, /Issue #2, not Issue #1/);
});

test('the round budget stops a loop that never passes', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.state.maxRounds = 2;
  run.addRound(verdict(identity(cwd), 'fail'));
  writeFileSync(join(cwd, 'feature.txt'), 'attempt two\n');
  run.addRound(verdict(identity(cwd), 'fail'));
  expectAction(run.next(), 'stop', 3);

  writeFileSync(join(cwd, 'feature.txt'), 'attempt three\n');
  const exhausted = run.next();
  expectAction(exhausted, 'stop', 3);
  assert.match(exhausted.decision.reason, /budget of 2/);
});

test('optional findings do not trigger another round', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd), 'pass', {
    findings: [{ id: 'F1', severity: 'optional', status: 'open', summary: 'Naming could be clearer', evidence: 'feature.txt:1' }],
  }));
  expectAction(run.next(), 'commit', 1);
});

test('a schema-invalid verdict stops the loop', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd), 'pass', { checks: [] }));
  expectAction(run.next(), 'stop', 3);
});

test('malformed state and options are rejected', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  for (const raw of [
    'not json',
    JSON.stringify({ schemaVersion: 2, issue: 1, questions: [], rounds: [] }),
    JSON.stringify({ schemaVersion: 1, issue: 0, questions: [], rounds: [] }),
    JSON.stringify({ schemaVersion: 1, issue: 1, maxRounds: 0, questions: [], rounds: [] }),
    JSON.stringify({ schemaVersion: 1, issue: 1, questions: [{ id: 'Q1', text: 'x', material: 'yes', status: 'open' }], rounds: [] }),
    JSON.stringify({ schemaVersion: 1, issue: 1, questions: [], rounds: [{}] }),
    JSON.stringify({ schemaVersion: 1, issue: 1, questions: [{ id: 'Q1', text: 'x', material: true, status: 'answered' }], rounds: [] }),
    JSON.stringify({ schemaVersion: 1, issue: 1, questions: [{ id: 'Q1', text: 'x', material: true, status: 'answered', answeredAfterRound: 1 }], rounds: [] }),
  ]) {
    assert.equal(run.write(raw).status, 2, raw);
  }

  run.addRound(verdict(identity(cwd)));
  for (const options of [['--comit', 'HEAD'], ['--commit', '--base'], ['--base', 'main', '--base', 'main']]) {
    assert.equal(run.next(options).status, 2, options.join(' '));
  }
  assert.equal(command(process.execPath, [SCRIPT], cwd).status, 2);
});

test('bad refs are usage errors, not verdict decisions', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd)));
  for (const options of [['--commit', 'HEADD'], ['--base', 'origin/nope']]) {
    const outcome = run.next(options);
    assert.equal(outcome.status, 2, options.join(' '));
    assert.match(outcome.stderr, /^ERROR: /);
  }
});

test('every round must be a valid verdict for the loop Issue', t => {
  const cwd = repository(t);
  const malformed = loop(t, cwd);
  malformed.addRound(verdict(identity(cwd), 'pass', { issue: '1' }));
  const invalid = malformed.next();
  expectAction(invalid, 'stop', 3);
  assert.match(invalid.decision.reason, /round 1 verdict is invalid/);

  const mixed = loop(t, cwd);
  mixed.addRound(verdict(identity(cwd), 'fail', { issue: 2 }));
  writeFileSync(join(cwd, 'feature.txt'), 'corrected\n');
  mixed.addRound(verdict(identity(cwd)));
  const other = mixed.next();
  expectAction(other, 'stop', 3);
  assert.match(other.decision.reason, /round 1 verdict is for Issue #2, not Issue #1/);
});

test('rounds beyond the budget stop even when blocked rounds end in a pass', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  const candidate = identity(cwd);
  for (let i = 0; i < 3; i++) run.addRound(verdict(candidate, 'blocked'));
  run.addRound(verdict(candidate, 'pass'));
  const stopped = run.next();
  expectAction(stopped, 'stop', 3);
  assert.match(stopped.decision.reason, /4 rounds exceed the round budget of 3/);
});

test('a worktree reverted to a failed candidate must be fixed, not re-verified', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd), 'fail'));
  writeFileSync(join(cwd, 'feature.txt'), 'corrected\n');
  run.addRound(verdict(identity(cwd)));
  writeFileSync(join(cwd, 'feature.txt'), 'implementation\n');
  const reverted = run.next();
  expectAction(reverted, 'fix', 1);
  assert.match(reverted.decision.reason, /identical to failed round 1/);
});

test('a failed uncommitted candidate is fixed, also when checked with --commit', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd), 'fail'));
  expectAction(run.next(['--commit', 'HEAD']), 'fix', 1);
});

test('a material answer after a failed round allows verifying the unchanged candidate again', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.state.questions.push({ id: 'Q1', text: 'Which roles may call it?', material: true, status: 'answered', answeredAfterRound: 1 });
  run.addRound(verdict(identity(cwd), 'fail'));
  expectAction(run.next(), 'verify', 1);
  run.addRound(verdict(identity(cwd)));
  expectAction(run.next(), 'commit', 1);
});

test('a round that drops an open blocker of the previous round stops the loop', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  const blocker = { id: 'F1', severity: 'blocker', status: 'open', criteria: ['AC1'], summary: 'Probe fails', evidence: 'check C1' };
  run.addRound(verdict(identity(cwd), 'fail', { findings: [blocker] }));

  writeFileSync(join(cwd, 'feature.txt'), 'corrected\n');
  run.addRound(verdict(identity(cwd)));
  const dropped = run.next();
  expectAction(dropped, 'stop', 3);
  assert.match(dropped.decision.reason, /round 2 verdict cannot be trusted: it drops open blocker finding\(s\) F1 of round 1/);
});

test('a blocker carried forward as resolved lets the corrected candidate pass', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  const blocker = { id: 'F1', severity: 'blocker', status: 'open', criteria: ['AC1'], summary: 'Probe fails', evidence: 'check C1' };
  run.addRound(verdict(identity(cwd), 'fail', { findings: [blocker] }));

  writeFileSync(join(cwd, 'feature.txt'), 'corrected\n');
  run.addRound(verdict(identity(cwd), 'pass', { findings: [{ ...blocker, status: 'resolved', evidence: 'kept probe reran and passed' }] }));
  expectAction(run.next(), 'commit', 1);
});

test('a latest verdict that does not record the adversarial pass adds a warning, never another action', t => {
  const cwd = repository(t);
  const run = loop(t, cwd);
  run.addRound(verdict(identity(cwd)));
  const unrecorded = run.next();
  expectAction(unrecorded, 'commit', 1);
  assert.equal(unrecorded.decision.warnings.length, 1);
  assert.match(unrecorded.decision.warnings[0], /does not say whether the adversarial pass ran/);

  const recorded = loop(t, cwd);
  recorded.addRound(verdict(identity(cwd), 'pass', { limitations: ['adversarial pass: not run: no trigger'] }));
  const outcome = recorded.next();
  expectAction(outcome, 'commit', 1);
  assert.equal(outcome.decision.warnings, undefined);
});
