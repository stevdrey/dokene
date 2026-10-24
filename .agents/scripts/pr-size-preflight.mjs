#!/usr/bin/env node

import { spawnSync } from 'node:child_process';

const MAX_FILES = 40;
const WARNING_FILES = 30;
const DEFAULT_BASE_REF = 'origin/main';

// This hook is a local convenience, not an authorization boundary: CI is authoritative.
const PUBLISH_COMMAND = /(?:^|[\s;&|()])(?:gh\s+pr\s+create|git\s+(?:(?:-C|-c|--git-dir|--work-tree)\s+(?:"[^"]*"|'[^']*'|\S+)\s+)*push)(?=$|[\s;&|()])/;
/**
 * `gh pr create` options that take a value (as listed by `gh pr create --help`), so a value that looks like an option
 * (a title "--base") is never read as one. Only exact --base, -B, --base=… and -B… words are read as the base, so an
 * option missing here can only make the preflight measure against main, which overcounts and never undercounts.
 */
const GH_VALUE_OPTIONS = new Set([
  '--title', '-t', '--body', '-b', '--body-file', '-F', '--head', '-H', '--label', '-l', '--assignee', '-a',
  '--reviewer', '-r', '--milestone', '-m', '--project', '-p', '--repo', '-R', '--template', '-T', '--recover',
  '--base', '-B',
]);
// Per-branch declaration of a stacked PR's base, for `git push` and the manual run (gh does not read it).
const BRANCH_BASE_KEY = 'prPreflightBase';
// The base `gh pr create` itself uses when --base is omitted (`gh pr create --help`), before the repository default.
const GH_MERGE_BASE_KEY = 'gh-merge-base';
// Revisions that always name the branch's own state, whatever they resolve to.
const OWN_STATE = /^(?:HEAD|@|@\{(?:u|upstream|push)\})(?:[~^].*)?$/;
// A shell that runs its next argument as a script, as an array payload may invoke it.
const SHELL = /(?:^|\/)(?:ba|z)?sh$/;

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

/** Whether a Git command exits successfully, for checks whose answer is the exit status alone. */
function gitSucceeds(cwd, ...args) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8', timeout: 10_000 });
  if (result.error) throw new Error(`git ${args[0]} failed: ${result.error.message}`);
  return result.status === 0;
}

function pathsFromNullSeparated(buffer) {
  return buffer.toString('utf8').split('\0').filter(Boolean);
}

/** The branch name a remote-tracking ref of origin names, in any of its spellings; null for anything else. */
function originBranch(ref) {
  const match = /^(?:refs\/remotes\/|remotes\/)?origin\/(.+)$/.exec(ref);
  return match ? match[1] : null;
}

function changedFiles(cwd, baseRef) {
  const root = git(cwd, 'rev-parse', '--show-toplevel').toString('utf8').trim();
  let base;
  try {
    base = git(root, 'merge-base', baseRef, 'HEAD').toString('utf8').trim();
  } catch (error) {
    throw new Error(`${error.message}. Refresh the base with 'git fetch origin ${originBranch(baseRef) ?? 'main'}' and retry`);
  }
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
 * The commands of a hook payload as word lists. A string is a shell command line. An array is already one command's
 * words and is used as is, so an argument with spaces stays one word, unless it runs a shell (`bash -lc "…"`), whose
 * script is then split like a command line.
 */
function commandWords(toolInput) {
  if (typeof toolInput.command === 'string') return shellCommands(toolInput.command);
  if (!Array.isArray(toolInput.cmd) || !toolInput.cmd.every(word => typeof word === 'string')) return [];
  const words = toolInput.cmd;
  if (words.length > 0 && SHELL.test(words[0])) {
    const script = words.findIndex((word, i) => i > 0 && /^-[a-z]*c$/.test(word));
    if (script !== -1 && script + 1 < words.length) return shellCommands(words[script + 1]);
  }
  return [words];
}

/**
 * Whether the commands create a PR with `gh`, and the base branch that create names (--base X, --base=X, -B X, -BX),
 * undefined when it names none. Values of other options are skipped, never read as options.
 */
function ghCreate(commands) {
  for (const words of commands) {
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
    return { base };
  }
  return null;
}

/**
 * A PR is measured against the branch it targets, never against its own published state, which would hide its
 * committed changes. The base must resolve to an existing commit, and is refused when it names the branch's own
 * state in any spelling: its own origin/ ref, HEAD, @, @{u} and their ~ and ^ forms, or any other revision (a commit
 * id, origin/<branch>~0) that resolves to the branch's upstream or HEAD. An exact remote-tracking branch other than
 * its own is accepted even at the same commit, as a freshly created stacked branch is. CI still counts the real PR,
 * for every target branch, so this only keeps the local estimate honest.
 */
function checkedBase(cwd, branch, ref) {
  const named = originBranch(ref);
  const own = `Base ${ref} is the branch being published, not the base it stacks on; name the branch it targets`;
  if (OWN_STATE.test(ref) || (branch && named === branch)) throw new Error(own);
  const commit = gitOptional(cwd, 'rev-parse', '--verify', '--quiet', `${ref}^{commit}`);
  if (!commit) {
    throw new Error(`Base ${ref} does not exist; fetch it with 'git fetch origin ${named ?? ref}'`);
  }
  // An exact ref only: show-ref accepts no revision expression, so origin/<branch>~0 is not an "other branch".
  const otherRemoteBranch = named !== null
      && gitSucceeds(cwd, 'show-ref', '--verify', '--quiet', `refs/remotes/origin/${named}`);
  if (!otherRemoteBranch) {
    const upstream = branch ? gitOptional(cwd, 'rev-parse', '--verify', '--quiet', `refs/remotes/origin/${branch}^{commit}`) : null;
    const head = gitOptional(cwd, 'rev-parse', '--verify', '--quiet', 'HEAD^{commit}');
    if (commit === upstream || commit === head) throw new Error(own);
  }
  return ref;
}

/** A branch name as `gh` uses it, always literal: `origin/staging` is a branch of that name, not `staging`. */
function ghBranchBase(cwd, branch, name) {
  return name === 'main' ? DEFAULT_BASE_REF : checkedBase(cwd, branch, `origin/${name}`);
}

/** A base this repository's own settings declare: a branch name, or already an `origin/` ref. */
function declaredBase(cwd, branch, name) {
  return checkedBase(cwd, branch, originBranch(name) !== null ? name : `origin/${name}`);
}

function branchConfig(cwd, branch, key) {
  return branch ? gitOptional(cwd, 'config', '--get', `branch.${branch}.${key}`) : null;
}

/**
 * The base the published PR will target, as each publish path decides it:
 * - `gh pr create` follows gh exactly: its --base, else the branch's `gh-merge-base`, else origin/main. gh reads
 *   neither PR_PREFLIGHT_BASE nor `prPreflightBase`, so neither applies to it.
 * - `git push` and the manual run: the manual --base, PR_PREFLIGHT_BASE, the branch's `prPreflightBase`, its
 *   `gh-merge-base`, else origin/main.
 */
function baseReference(args, cwd, branch, commands) {
  const create = ghCreate(commands);
  if (create) {
    if (create.base) return ghBranchBase(cwd, branch, create.base);
    const ghDefault = branchConfig(cwd, branch, GH_MERGE_BASE_KEY);
    return ghDefault ? ghBranchBase(cwd, branch, ghDefault) : DEFAULT_BASE_REF;
  }
  const index = args.indexOf('--base');
  if (index !== -1) {
    if (index + 1 >= args.length || !args[index + 1] || args[index + 1].startsWith('--')) {
      throw new Error('Provide a Git reference after --base');
    }
    return checkedBase(cwd, branch, args[index + 1]);
  }
  if (process.env.PR_PREFLIGHT_BASE) return checkedBase(cwd, branch, process.env.PR_PREFLIGHT_BASE);
  const declared = branchConfig(cwd, branch, BRANCH_BASE_KEY);
  if (declared) return declaredBase(cwd, branch, declared);
  const ghDefault = branchConfig(cwd, branch, GH_MERGE_BASE_KEY);
  return ghDefault ? ghBranchBase(cwd, branch, ghDefault) : DEFAULT_BASE_REF;
}

async function main() {
  const args = process.argv.slice(2);
  const fromHook = args.includes('--hook');
  let cwd = process.cwd();
  let commands = [];

  if (fromHook) {
    const payload = await hookInput();
    const toolInput = payload.tool_input;
    const command = typeof toolInput.command === 'string'
      ? toolInput.command
      : Array.isArray(toolInput.cmd) ? toolInput.cmd.join(' ') : '';

    if (!PUBLISH_COMMAND.test(command)) return;
    if (typeof payload.cwd === 'string' && payload.cwd) cwd = payload.cwd;
    commands = commandWords(toolInput);
  }

  // Resolved once and passed down: every base check and branch setting is about this branch.
  const branch = gitOptional(cwd, 'symbolic-ref', '--short', '-q', 'HEAD');
  const baseRef = baseReference(args, cwd, branch, commands);
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
  console.error(`BLOCKED: PR preflight could not verify the change scope: ${error.message}.`);
  process.exitCode = 2;
}
