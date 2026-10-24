import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, renameSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

const SCRIPT = new URL('./pr-size-preflight.mjs', import.meta.url).pathname;

function command(cmd, args, cwd, input) {
  return spawnSync(cmd, args, { cwd, encoding: 'utf8', input, timeout: 15_000 });
}

function git(cwd, ...args) {
  const result = command('git', args, cwd);
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function repository(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'dokene-pr-preflight-'));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  git(cwd, 'init', '-q', '-b', 'main');
  git(cwd, 'config', 'user.name', 'Test Agent');
  git(cwd, 'config', 'user.email', 'agent@example.invalid');
  writeFileSync(join(cwd, 'README.md'), 'baseline\n');
  git(cwd, 'add', '.');
  git(cwd, 'commit', '-qm', 'baseline');
  git(cwd, 'update-ref', 'refs/remotes/origin/main', 'HEAD');
  git(cwd, 'switch', '-qc', 'feature/preflight');
  return cwd;
}

function createFiles(cwd, amount) {
  mkdirSync(join(cwd, 'samples'), { recursive: true });
  for (let i = 0; i < amount; i++) {
    writeFileSync(join(cwd, 'samples', `file-${String(i).padStart(3, '0')}.txt`), 'test\n');
  }
}

function check(cwd, options = []) {
  return command(process.execPath, [SCRIPT, ...options], cwd);
}

function hook(cwd, shellCommand, overrides = {}) {
  return command(process.execPath, [SCRIPT, '--hook'], cwd, JSON.stringify({
    hook_event_name: 'PreToolUse',
    cwd,
    tool_name: 'Bash',
    tool_input: { command: shellCommand },
    ...overrides,
  }));
}

test('no changes passes', t => {
  const cwd = repository(t);
  const result = check(cwd);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /PASS: PR preflight: 0\/40/);
});

test('30 passes, 31 warns, 40 warns, and 41 blocks', t => {
  const cwd = repository(t);
  for (const [files, expectedStatus, message] of [
    [30, 0, /PASS:/],
    [31, 0, /WARNING:/],
    [40, 0, /WARNING:/],
    [41, 2, /BLOCKED:/],
  ]) {
    createFiles(cwd, files);
    const result = check(cwd);
    assert.equal(result.status, expectedStatus, result.stderr);
    assert.match(result.stdout + result.stderr, message);
    assert.match(result.stdout + result.stderr, new RegExp(`${files}/40`));
  }
});

test('committed, staged, unstaged, deleted, and untracked paths count once', t => {
  const cwd = repository(t);
  writeFileSync(join(cwd, 'README.md'), 'modified\n');
  git(cwd, 'add', 'README.md');
  writeFileSync(join(cwd, 'README.md'), 'modified again\n');
  writeFileSync(join(cwd, 'new.txt'), 'new\n');
  const result = check(cwd);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /2\/40/);

  git(cwd, 'add', '.');
  git(cwd, 'commit', '-qm', 'feature work');
  unlinkSync(join(cwd, 'README.md'));
  assert.match(check(cwd).stdout, /2\/40/);
});

test('renames count as one changed destination path', t => {
  const cwd = repository(t);
  renameSync(join(cwd, 'README.md'), join(cwd, 'RENAMED.md'));
  git(cwd, 'add', '-A');
  const result = check(cwd);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /1\/40/);
});

test('ignored files do not increase the count', t => {
  const cwd = repository(t);
  writeFileSync(join(cwd, '.gitignore'), '*.generated\n');
  writeFileSync(join(cwd, 'temp.generated'), 'ignored\n');
  assert.match(check(cwd).stdout, /1\/40/);
});

test('non-publication shell commands do not invoke Git validation', t => {
  const cwd = repository(t);
  git(cwd, 'update-ref', '-d', 'refs/remotes/origin/main');
  const result = hook(cwd, 'npm test');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stderr, '');
});

test('publication commands are blocked above the limit', t => {
  const cwd = repository(t);
  createFiles(cwd, 41);
  for (const shellCommand of ['git push origin HEAD', 'gh pr create --fill', 'git -C . push origin HEAD']) {
    const result = hook(cwd, shellCommand);
    assert.equal(result.status, 2, shellCommand);
    assert.match(result.stderr, /BLOCKED.*41\/40/);
  }
});

test('publication command is allowed below the limit', t => {
  const cwd = repository(t);
  createFiles(cwd, 2);
  const result = hook(cwd, 'git push origin HEAD');
  assert.equal(result.status, 0, result.stderr);
});

test('missing base reference fails closed and reports recovery', t => {
  const cwd = repository(t);
  git(cwd, 'update-ref', '-d', 'refs/remotes/origin/main');
  const result = hook(cwd, 'gh pr create --fill');
  assert.equal(result.status, 2);
  assert.match(result.stderr, /git fetch origin main/);
});

test('custom base reference supports intentionally stacked work', t => {
  const cwd = repository(t);
  createFiles(cwd, 41);
  git(cwd, 'add', '.');
  git(cwd, 'commit', '-qm', 'first stacked layer');
  git(cwd, 'update-ref', 'refs/remotes/origin/staging', 'HEAD');
  writeFileSync(join(cwd, 'added-second-layer.txt'), 'one\n');

  assert.equal(check(cwd).status, 2);
  const result = check(cwd, ['--base', 'origin/staging']);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /1\/40/);
});

/** A first layer of 41 files pushed as origin/staging, and one more file on top of it. */
function stackedRepository(t) {
  const cwd = repository(t);
  createFiles(cwd, 41);
  git(cwd, 'add', '.');
  git(cwd, 'commit', '-qm', 'first stacked layer');
  git(cwd, 'update-ref', 'refs/remotes/origin/staging', 'HEAD');
  writeFileSync(join(cwd, 'added-second-layer.txt'), 'one\n');
  return cwd;
}

test('a pull request created on top of another branch is measured against that branch', t => {
  const cwd = stackedRepository(t);
  for (const shellCommand of [
    'gh pr create --base staging --fill',
    'gh pr create --fill -B staging',
    'gh pr create --base=staging --fill',
    'gh pr create -Bstaging --fill',
    'gh pr create --base "staging" --title x --body-file body.md',
    // Option-looking text inside another option's value is never read as the base.
    'gh pr create --title "fix --base typo" --base staging --fill',
    'gh pr create --title --base --base staging --fill',
    'npm test && gh pr create --body "see -B main" -B staging',
  ]) {
    const result = hook(cwd, shellCommand);
    assert.equal(result.status, 0, `${shellCommand}: ${result.stderr}`);
  }
  for (const shellCommand of ['gh pr create --fill', 'gh pr create --base main --fill']) {
    const result = hook(cwd, shellCommand);
    assert.equal(result.status, 2, shellCommand);
    assert.match(result.stderr, /BLOCKED.*42\/40.*origin\/main/);
  }
});

test('a gh base is a literal branch name, never an already qualified ref', t => {
  const cwd = stackedRepository(t);
  // gh targets a branch named "origin/staging", which does not exist here, not the branch "staging".
  const result = hook(cwd, 'gh pr create --base=origin/staging --fill');
  assert.equal(result.status, 2);
  assert.match(result.stderr, /origin\/origin\/staging does not exist/);
});

test('gh pr create without --base uses the gh-merge-base its branch configures', t => {
  const cwd = stackedRepository(t);
  assert.equal(hook(cwd, 'gh pr create --fill').status, 2);
  git(cwd, 'config', 'branch.feature/preflight.gh-merge-base', 'staging');
  const result = hook(cwd, 'gh pr create --fill');
  assert.equal(result.status, 0, result.stderr);
  assert.match(check(cwd).stdout, /1\/40 changed files relative to origin\/staging/);
});

test('the manual and environment overrides are checked like any other base', t => {
  const cwd = stackedRepository(t);
  git(cwd, 'update-ref', 'refs/remotes/origin/feature/preflight', 'HEAD');
  const manual = check(cwd, ['--base', 'origin/feature/preflight']);
  assert.equal(manual.status, 2);
  assert.match(manual.stderr, /is the branch being published/);

  const fromEnv = spawnSync(process.execPath, [SCRIPT], {
    cwd, encoding: 'utf8', env: { ...process.env, PR_PREFLIGHT_BASE: 'origin/feature/preflight' },
  });
  assert.equal(fromEnv.status, 2);
  assert.match(fromEnv.stderr, /is the branch being published/);

  const missing = check(cwd, ['--base', 'origin/nowhere']);
  assert.equal(missing.status, 2);
  assert.match(missing.stderr, /origin\/nowhere does not exist/);
});

test('a push is measured against the base its branch declares', t => {
  const cwd = stackedRepository(t);
  assert.equal(hook(cwd, 'git push -u origin HEAD').status, 2);

  git(cwd, 'config', 'branch.feature/preflight.prPreflightBase', 'origin/staging');
  const result = hook(cwd, 'git push -u origin HEAD');
  assert.equal(result.status, 0, result.stderr);
  assert.match(check(cwd).stdout, /PASS: PR preflight: 1\/40 changed files relative to origin\/staging/);
});

test('a stacked base must exist and cannot be the published branch itself', t => {
  const cwd = stackedRepository(t);
  const missing = hook(cwd, 'gh pr create --base nowhere --fill');
  assert.equal(missing.status, 2);
  assert.match(missing.stderr, /origin\/nowhere does not exist/);

  git(cwd, 'update-ref', 'refs/remotes/origin/feature/preflight', 'HEAD');
  const itself = hook(cwd, 'gh pr create --base feature/preflight --fill');
  assert.equal(itself.status, 2);
  assert.match(itself.stderr, /is the branch being published/);

  git(cwd, 'config', 'branch.feature/preflight.prPreflightBase', 'feature/preflight');
  assert.equal(hook(cwd, 'git push origin HEAD').status, 2);
});

test('gh pr create follows gh: --base, else gh-merge-base, else main, never prPreflightBase', t => {
  const cwd = stackedRepository(t);
  // prPreflightBase is this repository's setting for pushes; gh ignores it and opens the PR against main.
  git(cwd, 'config', 'branch.feature/preflight.prPreflightBase', 'origin/staging');
  const ignored = hook(cwd, 'gh pr create --fill');
  assert.equal(ignored.status, 2);
  assert.match(ignored.stderr, /42\/40 changed files relative to origin\/main/);
  const envIgnored = spawnSync(process.execPath, [SCRIPT, '--hook'], {
    cwd, encoding: 'utf8', input: JSON.stringify({ cwd, tool_input: { command: 'gh pr create --fill' } }),
    env: { ...process.env, PR_PREFLIGHT_BASE: 'origin/staging' },
  });
  assert.equal(envIgnored.status, 2, 'PR_PREFLIGHT_BASE does not change the branch gh targets');

  git(cwd, 'config', 'branch.feature/preflight.gh-merge-base', 'staging');
  assert.equal(hook(cwd, 'gh pr create --fill').status, 0);
});

test('a push prefers PR_PREFLIGHT_BASE, then prPreflightBase, then gh-merge-base', t => {
  const cwd = stackedRepository(t);
  git(cwd, 'config', 'branch.feature/preflight.gh-merge-base', 'nowhere');
  git(cwd, 'config', 'branch.feature/preflight.prPreflightBase', 'origin/staging');
  assert.equal(hook(cwd, 'git push origin HEAD').status, 0, 'prPreflightBase wins over gh-merge-base');

  git(cwd, 'config', '--unset', 'branch.feature/preflight.prPreflightBase');
  const ghDefault = hook(cwd, 'git push origin HEAD');
  assert.equal(ghDefault.status, 2);
  assert.match(ghDefault.stderr, /origin\/nowhere does not exist; fetch it with 'git fetch origin nowhere'/);

  const fromEnv = spawnSync(process.execPath, [SCRIPT, '--hook'], {
    cwd, encoding: 'utf8', input: JSON.stringify({ cwd, tool_input: { command: 'git push origin HEAD' } }),
    env: { ...process.env, PR_PREFLIGHT_BASE: 'origin/staging' },
  });
  assert.equal(fromEnv.status, 0, fromEnv.stderr);
});

test('an argument array keeps its words, and a shell array is split like a command line', t => {
  const cwd = stackedRepository(t);
  for (const cmd of [
    ['gh', 'pr', 'create', '--base', 'staging', '--title', 'use --base main'],
    ['gh', 'pr', 'create', '--title', 'fix --base typo', '-B', 'staging'],
    ['bash', '-lc', 'gh pr create --title "use --base main" --base staging'],
  ]) {
    const result = hook(cwd, '', { tool_input: { cmd } });
    assert.equal(result.status, 0, `${JSON.stringify(cmd)}: ${result.stderr}`);
  }
  const titled = hook(cwd, '', { tool_input: { cmd: ['gh', 'pr', 'create', '--title', 'see --base staging'] } });
  assert.equal(titled.status, 2, 'a title is never the base');
});

test('the branch\'s own published state is refused in any spelling, a real base at the same commit is not', t => {
  const cwd = stackedRepository(t);
  git(cwd, 'update-ref', 'refs/remotes/origin/feature/preflight', 'HEAD');
  const head = git(cwd, 'rev-parse', 'HEAD');
  for (const base of ['HEAD', '@', '@{u}', 'HEAD~0', 'remotes/origin/feature/preflight',
    'refs/remotes/origin/feature/preflight', 'origin/feature/preflight~0', head]) {
    const result = check(cwd, ['--base', base]);
    assert.equal(result.status, 2, base);
    assert.match(result.stderr, /is the branch being published, not the base it stacks on; name the branch it targets/, base);
    assert.doesNotMatch(result.stderr, /git fetch origin main/, base);
  }
  // origin/staging is the branch this one stacks on and happens to point at the same commit.
  const real = check(cwd, ['--base', 'origin/staging']);
  assert.equal(real.status, 0, real.stderr);
  assert.match(real.stdout, /1\/40/);
});

test('malformed hook payload fails closed', t => {
  const cwd = repository(t);
  const result = command(process.execPath, [SCRIPT, '--hook'], cwd, '{malformed json');
  assert.equal(result.status, 2);
  assert.match(result.stderr, /BLOCKED/);
});
