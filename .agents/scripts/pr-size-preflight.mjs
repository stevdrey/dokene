#!/usr/bin/env node

import { spawnSync } from 'node:child_process';

const MAX_FILES = 40;
const WARNING_FILES = 30;
const DEFAULT_BASE_REF = 'origin/main';

// This hook is a local convenience, not an authorization boundary: CI is authoritative.
const PUBLISH_COMMAND = /(?:^|[\s;&|()])(?:gh\s+pr\s+create|git\s+(?:(?:-C|-c|--git-dir|--work-tree)\s+(?:"[^"]*"|'[^']*'|\S+)\s+)*push)(?=$|[\s;&|()])/;
// The base branch a `gh pr create` names with --base, -B or --base=, quoted or not.
const PR_CREATE_BASE = /(?:^|[\s;&|()])gh\s+pr\s+create\b[^;&|]*?\s(?:--base|-B)(?:=|\s+)("[^"]*"|'[^']*'|[^\s;&|()]+)/;
// Per-branch declaration of a stacked PR's base, for publish commands that do not name one (`git push`).
const BRANCH_BASE_KEY = 'prPreflightBase';

function git(cwd, ...args) {
  const result = spawnSync('git', args, {
    cwd,
    encoding: 'buffer',
    timeout: 10_000,
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.error || result.status !== 0) {
    const detail = result.stderr?.toString('utf8').trim() || result.error?.message || 'Unknown Git error';
    throw new Error(`git ${args[0]} failed: ${detail}`);
  }
  return result.stdout;
}

/** Like git(), but a non-zero exit (unset config, detached HEAD) answers null instead of failing. */
function gitOptional(cwd, ...args) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8', timeout: 10_000 });
  if (result.error) throw new Error(`git ${args[0]} failed: ${result.error.message}`);
  return result.status === 0 ? result.stdout.trim() || null : null;
}

function pathsFromNullSeparated(buffer) {
  return buffer.toString('utf8').split('\0').filter(Boolean);
}

function changedFiles(cwd, baseRef) {
  const root = git(cwd, 'rev-parse', '--show-toplevel').toString('utf8').trim();
  const base = git(root, 'merge-base', baseRef, 'HEAD').toString('utf8').trim();
  if (!base) throw new Error(`No merge base found for ${baseRef}`);

  // Comparing a commit to the worktree includes committed, staged, and unstaged changes.
  const tracked = git(root, 'diff', '--name-only', '-z', base, '--');
  const untracked = git(root, 'ls-files', '--others', '--exclude-standard', '-z');
  return new Set([...pathsFromNullSeparated(tracked), ...pathsFromNullSeparated(untracked)]).size;
}

async function hookInput() {
  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  const value = JSON.parse(input);
  if (!value || typeof value !== 'object' || !value.tool_input || typeof value.tool_input !== 'object') {
    throw new Error('Invalid PreToolUse input: tool_input is missing');
  }
  return value;
}

function unquote(value) {
  return /^(["']).*\1$/.test(value) ? value.slice(1, -1) : value;
}

/**
 * A stacked PR is measured against the branch it targets, never against itself. The base must be an existing
 * remote-tracking branch other than the current branch's own, so a branch cannot hide its changes by naming its
 * already pushed state as the base. CI still counts the real PR, so this only keeps the local estimate honest.
 */
function stackedBase(cwd, name) {
  const ref = name.startsWith('origin/') ? name : `origin/${name}`;
  const branch = gitOptional(cwd, 'symbolic-ref', '--short', '-q', 'HEAD');
  if (branch && ref === `origin/${branch}`) {
    throw new Error(`${ref} is the branch being published, not the base it stacks on`);
  }
  if (!gitOptional(cwd, 'rev-parse', '--verify', '--quiet', `${ref}^{commit}`)) {
    throw new Error(`Base ${ref} does not exist; fetch it with 'git fetch origin ${ref.slice('origin/'.length)}'`);
  }
  return ref;
}

/**
 * In order: the manual --base argument, the base a `gh pr create` names, PR_PREFLIGHT_BASE, the current branch's
 * `branch.<name>.prPreflightBase` setting, and otherwise origin/main.
 */
function baseReference(args, cwd, command) {
  const index = args.indexOf('--base');
  if (index !== -1) {
    if (index + 1 >= args.length || !args[index + 1] || args[index + 1].startsWith('--')) {
      throw new Error('Provide a Git reference after --base');
    }
    return args[index + 1];
  }
  const named = command ? PR_CREATE_BASE.exec(command) : null;
  if (named) {
    const base = unquote(named[1]);
    return base === 'main' || base === DEFAULT_BASE_REF ? DEFAULT_BASE_REF : stackedBase(cwd, base);
  }
  if (process.env.PR_PREFLIGHT_BASE) return process.env.PR_PREFLIGHT_BASE;
  const branch = gitOptional(cwd, 'symbolic-ref', '--short', '-q', 'HEAD');
  const declared = branch ? gitOptional(cwd, 'config', '--get', `branch.${branch}.${BRANCH_BASE_KEY}`) : null;
  return declared ? stackedBase(cwd, declared) : DEFAULT_BASE_REF;
}

async function main() {
  const args = process.argv.slice(2);
  const fromHook = args.includes('--hook');
  let cwd = process.cwd();
  let command = '';

  if (fromHook) {
    const payload = await hookInput();
    const toolInput = payload.tool_input;
    command = typeof toolInput.command === 'string'
      ? toolInput.command
      : Array.isArray(toolInput.cmd) ? toolInput.cmd.join(' ') : '';

    if (!PUBLISH_COMMAND.test(command)) return;
    if (typeof payload.cwd === 'string' && payload.cwd) cwd = payload.cwd;
  }

  const baseRef = baseReference(args, cwd, command);
  const count = changedFiles(cwd, baseRef);
  const result = `PR preflight: ${count}/${MAX_FILES} changed files relative to ${baseRef}.`;

  if (count > MAX_FILES) {
    console.error(`BLOCKED: ${result} Split this work into smaller, independently reviewable PRs before publishing. Preserve required tests and security controls.`);
    process.exitCode = 2;
  } else if (count > WARNING_FILES) {
    console.error(`WARNING: ${result} Review scope before adding more changes.`);
  } else if (!fromHook) {
    console.log(`PASS: ${result}`);
  }
}

try {
  await main();
} catch (error) {
  console.error(`BLOCKED: PR preflight could not verify the change scope: ${error.message}. Refresh the base reference (for example 'git fetch origin main') and retry.`);
  process.exitCode = 2;
}
