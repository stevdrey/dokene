#!/usr/bin/env node

import { spawnSync } from 'node:child_process';

const MAX_FILES = 40;
const WARNING_FILES = 30;
const DEFAULT_BASE_REF = 'origin/main';

// This hook is a local convenience, not an authorization boundary: CI is authoritative.
const PUBLISH_COMMAND = /(?:^|[\s;&|()])(?:gh\s+pr\s+create|git\s+(?:(?:-C|-c|--git-dir|--work-tree)\s+(?:"[^"]*"|'[^']*'|\S+)\s+)*push)(?=$|[\s;&|()])/;
// `gh pr create` options that take a value, so a value that looks like an option (a title "--base") is never read as one.
const GH_VALUE_OPTIONS = new Set([
  '--title', '-t', '--body', '-b', '--body-file', '-F', '--head', '-H', '--label', '-l', '--assignee', '-a',
  '--reviewer', '-r', '--milestone', '-m', '--project', '-p', '--repo', '-R', '--template', '-T', '--base', '-B',
]);
// Per-branch declaration of a stacked PR's base, for publish commands that do not name one (`git push`).
const BRANCH_BASE_KEY = 'prPreflightBase';
// The base `gh pr create` itself uses when --base is omitted (`gh pr create --help`), before the repository default.
const GH_MERGE_BASE_KEY = 'gh-merge-base';

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

/**
 * Splits a shell command into words the way a POSIX shell would for these purposes: quotes group and are removed, a
 * backslash escapes the next character, and unquoted ;, &, |, ( and ) end a command. Each command is a list of words.
 */
function shellCommands(text) {
  const commands = [[]];
  let word = null;
  let quote = null;
  const end = () => {
    if (word !== null) commands[commands.length - 1].push(word);
    word = null;
  };
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === quote) quote = null;
      else if (c === '\\' && quote === '"' && i + 1 < text.length) word += text[++i];
      else word += c;
    } else if (c === '"' || c === "'") {
      quote = c;
      word ??= '';
    } else if (c === '\\' && i + 1 < text.length) {
      word = (word ?? '') + text[++i];
    } else if (/\s/.test(c)) {
      end();
    } else if (';&|()'.includes(c)) {
      end();
      if (commands[commands.length - 1].length > 0) commands.push([]);
    } else {
      word = (word ?? '') + c;
    }
  }
  end();
  return commands.filter(words => words.length > 0);
}

/**
 * The base branch a `gh pr create` in the command names (--base X, --base=X, -B X, -BX), undefined when a create
 * names none, or null when the command creates no PR. Values of other options are skipped, never read as options.
 */
function ghCreateBase(command) {
  for (const words of shellCommands(command)) {
    const at = words.findIndex((word, i) => word === 'gh' && words[i + 1] === 'pr' && words[i + 2] === 'create');
    if (at === -1) continue;
    let base;
    for (let i = at + 3; i < words.length; i++) {
      const word = words[i];
      if (word === '--base' || word === '-B') {
        if (i + 1 >= words.length) throw new Error('gh pr create names --base without a branch');
        base = words[++i];
      } else if (word.startsWith('--base=')) {
        base = word.slice('--base='.length);
      } else if (word.startsWith('-B') && word.length > 2) {
        base = word.slice(2);
      } else if (GH_VALUE_OPTIONS.has(word)) {
        i++;
      }
    }
    return base;
  }
  return null;
}

/**
 * A PR is measured against the branch it targets, never against itself. Every base, however it was given, must
 * resolve to an existing commit and must not be the current branch's own remote-tracking ref, so a branch cannot hide
 * its changes by naming its already pushed state as the base. CI still counts the real PR (for every target branch),
 * so this only keeps the local estimate honest.
 */
function checkedBase(cwd, ref) {
  const branch = gitOptional(cwd, 'symbolic-ref', '--short', '-q', 'HEAD');
  if (branch && (ref === `origin/${branch}` || ref === `refs/remotes/origin/${branch}`)) {
    throw new Error(`${ref} is the branch being published, not the base it stacks on`);
  }
  if (!gitOptional(cwd, 'rev-parse', '--verify', '--quiet', `${ref}^{commit}`)) {
    const name = ref.startsWith('origin/') ? ref.slice('origin/'.length) : ref;
    throw new Error(`Base ${ref} does not exist; fetch it with 'git fetch origin ${name}'`);
  }
  return ref;
}

/** A branch name as `gh` uses it, always literal: `origin/staging` is a branch of that name, not `staging`. */
function ghBranchBase(cwd, name) {
  return name === 'main' ? DEFAULT_BASE_REF : checkedBase(cwd, `origin/${name}`);
}

/** A base this repository's own settings declare: a branch name, or already an `origin/` ref. */
function declaredBase(cwd, name) {
  return checkedBase(cwd, name.startsWith('origin/') ? name : `origin/${name}`);
}

function branchConfig(cwd, key) {
  const branch = gitOptional(cwd, 'symbolic-ref', '--short', '-q', 'HEAD');
  return branch ? gitOptional(cwd, 'config', '--get', `branch.${branch}.${key}`) : null;
}

/**
 * In order: the manual --base argument, the base a `gh pr create` names, PR_PREFLIGHT_BASE, the current branch's
 * `branch.<name>.prPreflightBase` setting, its `branch.<name>.gh-merge-base` setting (which `gh pr create` itself uses
 * when no --base is given), and otherwise origin/main.
 */
function baseReference(args, cwd, command) {
  const index = args.indexOf('--base');
  if (index !== -1) {
    if (index + 1 >= args.length || !args[index + 1] || args[index + 1].startsWith('--')) {
      throw new Error('Provide a Git reference after --base');
    }
    return checkedBase(cwd, args[index + 1]);
  }
  const named = command ? ghCreateBase(command) : null;
  if (named) return ghBranchBase(cwd, named);
  if (process.env.PR_PREFLIGHT_BASE) return checkedBase(cwd, process.env.PR_PREFLIGHT_BASE);
  const declared = branchConfig(cwd, BRANCH_BASE_KEY);
  if (declared) return declaredBase(cwd, declared);
  const ghDefault = branchConfig(cwd, GH_MERGE_BASE_KEY);
  return ghDefault ? ghBranchBase(cwd, ghDefault) : DEFAULT_BASE_REF;
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
