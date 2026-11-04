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
  verification-verdict.mjs --verdict <file> [--base <ref>]`;

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
  let base;
  try {
    base = git(root, 'merge-base', baseRef, 'HEAD').toString('utf8').trim();
  } catch (error) {
    throw new Error(`${error.message}. Fetch the base ref and retry, or pass --base`);
  }

  const scratch = mkdtempSync(join(tmpdir(), 'dokene-candidate-'));
  try {
    const env = { ...process.env, GIT_INDEX_FILE: join(scratch, 'index') };
    gitWith(root, env, 'read-tree', 'HEAD');
    gitWith(root, env, 'add', '--all', '--', '.');
    const tree = gitWith(root, env, 'write-tree').toString('utf8').trim();
    return { base, head, tree };
  } finally {
    rmSync(scratch, { recursive: true, force: true });
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
    if (!Array.isArray(criterion.evidence)) errors.push(`${where}.evidence must be an array of check ids`);
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
    if (check.candidate !== undefined) validateCandidate(check.candidate, `${where}.candidate`, errors);
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
  const stale = check => check.candidate !== undefined
    && !sameCandidate(check.candidate, verdict.candidate)
    && !isText(check.reusedBecause);

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

function option(args, name) {
  const index = args.indexOf(name);
  if (index === -1) return null;
  const value = args[index + 1];
  if (!value || value.startsWith('--')) throw new UsageError(`${name} requires a value`);
  return value;
}

class UsageError extends Error {}

function main() {
  const args = process.argv.slice(2);
  const baseRef = option(args, '--base') ?? DEFAULT_BASE_REF;

  if (args.includes('--identity')) {
    console.log(JSON.stringify(candidateIdentity(process.cwd(), baseRef), null, 2));
    return;
  }

  const file = option(args, '--verdict');
  if (!file) throw new UsageError(USAGE);
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

  // Always compared, so exit 0 can gate publication: a pass is only ever about the content in this worktree.
  const current = candidateIdentity(process.cwd(), baseRef);
  if (!sameCandidate(current, verdict.candidate)) {
    console.error(`STALE: verdict is for ${describe(verdict.candidate)}, but the worktree is ${describe(current)}. Run a fresh independent verification.`);
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
    console.log(`PASS: ${subject}: ${verdict.criteria.length} criteria verified.`);
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
