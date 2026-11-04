#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const SCHEMA_VERSION = 1;
const DEFAULT_BASE_REF = 'origin/main';
const VERDICTS = new Set(['pass', 'fail', 'blocked']);
const CRITERION_RESULTS = VERDICTS;
const CHECK_RESULTS = new Set(['passed', 'failed', 'not-run']);
const CHECK_KINDS = new Set(['automated', 'manual', 'simulated', 'live']);
const FINDING_SEVERITIES = new Set(['blocker', 'optional']);
const FINDING_STATUSES = new Set(['open', 'resolved']);
const OBJECT_ID = /^[0-9a-f]{40}(?:[0-9a-f]{24})?$/;

const USAGE = `Usage:
  verification-verdict.mjs --identity [--base <ref>]
  verification-verdict.mjs --verdict <file> [--commit <rev>] [--base <ref>]`;

function git(cwd, ...args) {
  return gitWith(cwd, process.env, ...args);
}

function gitWith(cwd, env, ...args) {
  const result = spawnSync('git', args, {
    cwd,
    env,
    encoding: 'buffer',
    timeout: 10_000,
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error || result.status !== 0) {
    const detail = result.stderr?.toString('utf8').trim() || result.error?.message || 'Unknown Git error';
    throw new Error(`git ${args[0]} failed: ${detail}`);
  }
  return result.stdout;
}

/**
 * The exact candidate in the worktree: its base, its commit, and the Git tree of its content, including every
 * staged, unstaged, and untracked change that is not ignored. The tree is built in a throwaway index, so the real
 * index is untouched, and it is content-addressed, so committing the verified content keeps the same identity while
 * any edit changes it. Evidence gathered before an edit can therefore never silently stand for the edited code.
 */
function candidateIdentity(cwd, baseRef = DEFAULT_BASE_REF) {
  const root = git(cwd, 'rev-parse', '--show-toplevel').toString('utf8').trim();
  const head = git(root, 'rev-parse', 'HEAD').toString('utf8').trim();
  const base = mergeBase(root, baseRef, head);

  const scratch = mkdtempSync(join(tmpdir(), 'dokene-candidate-'));
  try {
    // A fresh index from HEAD on purpose: a copy of the real one would carry its stat cache, assume-unchanged and
    // skip-worktree bits, and racy-clean timestamps, each of which can make `git add` skip a real worktree edit.
    const env = { ...process.env, GIT_INDEX_FILE: join(scratch, 'index') };
    gitWith(root, env, 'read-tree', 'HEAD');
    gitWith(root, env, 'add', '--all', '--', '.');
    const tree = gitWith(root, env, 'write-tree').toString('utf8').trim();
    return { base, head, tree };
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

/**
 * The candidate a commit holds, ignoring the worktree: what is actually published when that commit is pushed. A
 * verified change left unstaged or out of a partial commit therefore makes the commit a different candidate.
 */
function commitIdentity(cwd, rev, baseRef = DEFAULT_BASE_REF) {
  const root = git(cwd, 'rev-parse', '--show-toplevel').toString('utf8').trim();
  const head = git(root, 'rev-parse', '--verify', '--end-of-options', `${rev}^{commit}`).toString('utf8').trim();
  const tree = git(root, 'rev-parse', `${head}^{tree}`).toString('utf8').trim();
  return { base: mergeBase(root, baseRef, head), head, tree };
}

function mergeBase(root, baseRef, head) {
  try {
    return git(root, 'merge-base', '--end-of-options', baseRef, head).toString('utf8').trim();
  } catch (error) {
    throw new Error(`${error.message}. Fetch the base ref and retry, or pass --base`);
  }
}

// The commit is recorded for traceability only: the same content on a new commit is the same candidate.
function sameCandidate(a, b) {
  return a.base === b.base && a.tree === b.tree;
}

function describe(candidate) {
  return `tree ${candidate.tree.slice(0, 12)} at ${candidate.head.slice(0, 12)} (base ${candidate.base.slice(0, 12)})`;
}

const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const isText = value => typeof value === 'string' && value.trim() !== '';

function validateCandidate(candidate, where, errors) {
  if (!isObject(candidate)) {
    errors.push(`${where} must be an object with base, head and tree`);
    return;
  }
  if (!OBJECT_ID.test(candidate.base ?? '')) errors.push(`${where}.base must be a full commit id`);
  if (!OBJECT_ID.test(candidate.head ?? '')) errors.push(`${where}.head must be a full commit id`);
  if (!OBJECT_ID.test(candidate.tree ?? '')) errors.push(`${where}.tree must be a full tree id`);
}

function uniqueIds(items, where, errors) {
  const ids = new Set();
  for (const [index, item] of items.entries()) {
    if (!isObject(item)) errors.push(`${where}[${index}] must be an object`);
    else if (!isText(item.id)) errors.push(`${where}[${index}].id is required`);
    else if (ids.has(item.id)) errors.push(`${where} id ${item.id} is duplicated`);
    else ids.add(item.id);
  }
}

/** Schema problems that make a verdict unreadable; any of them rejects the verdict outright. */
function validateVerdict(verdict) {
  const errors = [];
  if (!isObject(verdict)) return ['verdict must be a JSON object'];
  if (verdict.schemaVersion !== SCHEMA_VERSION) errors.push(`schemaVersion must be ${SCHEMA_VERSION}`);
  if (!Number.isInteger(verdict.issue) || verdict.issue <= 0) errors.push('issue must be a positive Issue number');
  validateCandidate(verdict.candidate, 'candidate', errors);
  if (!isObject(verdict.verifier) || typeof verdict.verifier.independent !== 'boolean') {
    errors.push('verifier.independent must be true or false');
  }
  if (!VERDICTS.has(verdict.verdict)) errors.push('verdict must be pass, fail or blocked');
  for (const key of ['criteria', 'findings', 'checks', 'limitations', 'remainingActions']) {
    if (!Array.isArray(verdict[key])) errors.push(`${key} must be an array`);
  }
  if (errors.length) return errors;

  uniqueIds(verdict.criteria, 'criteria', errors);
  uniqueIds(verdict.findings, 'findings', errors);
  uniqueIds(verdict.checks, 'checks', errors);
  if (errors.length) return errors;
  const criterionIds = new Set(verdict.criteria.map(criterion => criterion.id));

  verdict.criteria.forEach((criterion, index) => {
    const where = `criteria[${index}]`;
    if (!isText(criterion.text)) errors.push(`${where}.text is required`);
    if (!CRITERION_RESULTS.has(criterion.result)) errors.push(`${where}.result must be pass, fail or blocked`);
    if (!Array.isArray(criterion.evidence) || !criterion.evidence.every(isText)) {
      errors.push(`${where}.evidence must be an array of check ids`);
    }
  });
  verdict.findings.forEach((finding, index) => {
    const where = `findings[${index}]`;
    if (!FINDING_SEVERITIES.has(finding.severity)) errors.push(`${where}.severity must be blocker or optional`);
    if (!FINDING_STATUSES.has(finding.status)) errors.push(`${where}.status must be open or resolved`);
    if (!isText(finding.summary)) errors.push(`${where}.summary is required`);
    if (!isText(finding.evidence)) errors.push(`${where}.evidence must give a reproduction or supporting evidence`);
    if (finding.criteria !== undefined
        && (!Array.isArray(finding.criteria) || finding.criteria.some(id => !criterionIds.has(id)))) {
      errors.push(`${where}.criteria must list ids of mapped criteria`);
    }
  });
  verdict.checks.forEach((check, index) => {
    const where = `checks[${index}]`;
    if (!CHECK_KINDS.has(check.kind)) errors.push(`${where}.kind must be automated, manual, simulated or live`);
    if (!CHECK_RESULTS.has(check.result)) errors.push(`${where}.result must be passed, failed or not-run`);
    if (typeof check.required !== 'boolean') errors.push(`${where}.required must be true or false`);
    if (!isText(check.command)) errors.push(`${where}.command must state what was run or done`);
    // Required, so evidence carried over from another candidate can never pass as fresh by omitting where it ran.
    validateCandidate(check.candidate, `${where}.candidate`, errors);
    if (check.reusedBecause !== undefined && !isText(check.reusedBecause)) {
      errors.push(`${where}.reusedBecause must explain why older evidence still applies`);
    }
  });
  return errors;
}

/**
 * The verdict the evidence supports. Any failure is actionable and wins over missing evidence; missing evidence
 * (an unavailable verifier, environment or check) can never produce a pass.
 */
function computeVerdict(verdict) {
  const failures = [];
  const blockers = [];
  const checks = new Map(verdict.checks.map(check => [check.id, check]));
  const stale = check => !sameCandidate(check.candidate, verdict.candidate) && !isText(check.reusedBecause);

  if (verdict.verifier.independent !== true) blockers.push('the verifier was not an independent subagent');
  if (verdict.criteria.length === 0) blockers.push('no acceptance criteria were mapped');

  for (const criterion of verdict.criteria) {
    if (criterion.result === 'fail') failures.push(`criterion ${criterion.id} failed`);
    if (criterion.result === 'blocked') blockers.push(`criterion ${criterion.id} could not be verified`);
    if (criterion.result !== 'pass') continue;
    if (criterion.evidence.length === 0) blockers.push(`criterion ${criterion.id} passes without evidence`);
    for (const id of criterion.evidence) {
      const check = checks.get(id);
      if (!check) blockers.push(`criterion ${criterion.id} cites unknown evidence ${id}`);
      else if (check.result !== 'passed') blockers.push(`criterion ${criterion.id} cites ${id}, which did not pass`);
      else if (stale(check)) blockers.push(`criterion ${criterion.id} cites stale evidence ${id}`);
    }
  }
  for (const finding of verdict.findings) {
    if (finding.severity === 'blocker' && finding.status === 'open') {
      failures.push(`blocker finding ${finding.id} is open: ${finding.summary}`);
    }
  }
  for (const check of verdict.checks) {
    if (!check.required) continue;
    if (check.result === 'failed') failures.push(`required check ${check.id} failed`);
    else if (check.result === 'not-run') blockers.push(`required check ${check.id} was not run`);
    else if (stale(check)) blockers.push(`required check ${check.id} is stale evidence from another candidate`);
  }

  const result = failures.length ? 'fail' : blockers.length ? 'blocked' : 'pass';
  return { result, reasons: [...failures, ...blockers] };
}

// Every option each mode accepts. Anything else is rejected, so a misspelled --commit can never silently fall back
// to checking the worktree, which would let the publication gate pass a commit that lacks the verified change.
const MODES = {
  '--identity': new Set(['--base']),
  '--verdict': new Set(['--base', '--commit']),
};

const KNOWN_OPTIONS = new Set([...Object.keys(MODES), ...Object.values(MODES).flatMap(names => [...names])]);

/** Strict options: `--name value` or `--name=value`, each at most once, and only those the mode accepts. */
function parseArgs(args) {
  const options = new Map();
  for (let i = 0; i < args.length; i++) {
    const [name, inline] = args[i].startsWith('--') && args[i].includes('=')
      ? [args[i].slice(0, args[i].indexOf('=')), args[i].slice(args[i].indexOf('=') + 1)]
      : [args[i], undefined];
    if (!KNOWN_OPTIONS.has(name)) {
      throw new UsageError(`unknown argument ${args[i]}\n${USAGE}`);
    }
    if (options.has(name)) throw new UsageError(`${name} given more than once`);
    if (name === '--identity') {
      if (inline !== undefined) throw new UsageError('--identity takes no value');
      options.set(name, true);
      continue;
    }
    const value = inline ?? args[++i];
    if (!value) throw new UsageError(`${name} requires a value`);
    // Refs are passed on to Git, which would read a leading '-' as an option; a verdict path is only read as a file.
    if (name !== '--verdict' && value.startsWith('-')) throw new UsageError(`${name} value may not start with '-'`);
    options.set(name, value);
  }

  const modes = Object.keys(MODES).filter(mode => options.has(mode));
  if (modes.length !== 1) throw new UsageError(USAGE);
  const [mode] = modes;
  for (const name of options.keys()) {
    if (name !== mode && !MODES[mode].has(name)) throw new UsageError(`${name} cannot be used with ${mode}`);
  }
  return options;
}

class UsageError extends Error {}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const baseRef = options.get('--base') ?? DEFAULT_BASE_REF;

  if (options.has('--identity')) {
    console.log(JSON.stringify(candidateIdentity(process.cwd(), baseRef), null, 2));
    return;
  }

  const file = options.get('--verdict');
  let verdict;
  try {
    verdict = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    throw new UsageError(`cannot read verdict ${file}: ${error.message}`);
  }

  const errors = validateVerdict(verdict);
  if (errors.length) {
    console.error('INVALID: verification verdict does not match schema version 1:');
    for (const error of errors) console.error(`- ${error}`);
    process.exitCode = 2;
    return;
  }

  // Always compared, so exit 0 means the verdict is about this worktree, or with --commit about that commit.
  const commit = options.get('--commit');
  const current = commit ? commitIdentity(process.cwd(), commit, baseRef) : candidateIdentity(process.cwd(), baseRef);
  const checked = commit ? `commit ${commit} (${current.head.slice(0, 12)})` : 'the worktree';
  if (!sameCandidate(current, verdict.candidate)) {
    console.error(`STALE: verdict is for ${describe(verdict.candidate)}, but ${checked} is ${describe(current)}. Run a fresh independent verification.`);
    process.exitCode = 2;
    return;
  }

  const { result, reasons } = computeVerdict(verdict);
  const subject = `Issue #${verdict.issue} candidate ${describe(verdict.candidate)}`;
  if (verdict.verdict !== result) {
    console.error(`INCONSISTENT: declared ${verdict.verdict}, but the evidence supports ${result} for ${subject}.`);
    for (const reason of reasons) console.error(`- ${reason}`);
    process.exitCode = 2;
    return;
  }
  if (result === 'pass') {
    console.log(`PASS: ${subject}: ${verdict.criteria.length} criteria verified; checked against ${checked}.`);
    return;
  }
  console.error(`${result.toUpperCase()}: ${subject}:`);
  for (const reason of reasons) console.error(`- ${reason}`);
  process.exitCode = 1;
}

try {
  main();
} catch (error) {
  console.error(error instanceof UsageError ? error.message : `INVALID: ${error.message}.`);
  process.exitCode = 2;
}
