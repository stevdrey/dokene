#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import {
  DEFAULT_BASE_REF, UsageError, candidateIdentity, commitIdentity, computeVerdict, describe, isObject, isText,
  parseOptions, sameCandidate, validateVerdict,
} from './verification-verdict.mjs';

const SCHEMA_VERSION = 1;
const DEFAULT_MAX_ROUNDS = 3;
const QUESTION_STATUSES = new Set(['open', 'answered']);

// Exit codes: the loop may publish, should continue with the printed action, must stop, or could not decide.
const EXIT = { publish: 0, ask: 1, verify: 1, fix: 1, commit: 1, stop: 3 };

const USAGE = `Usage:
  issue-loop.mjs --state <file> [--commit <rev>] [--base <ref>]`;

function parseArgs(args) {
  // The state path is only read as a file; the other values reach Git as refs.
  const options = parseOptions(args, { values: ['--state', '--commit', '--base'], rawValues: ['--state'] }, USAGE);
  if (!options.has('--state')) throw new UsageError(USAGE);
  return options;
}

function readJson(file, what) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    throw new UsageError(`cannot read ${what} ${file}: ${error.message}`);
  }
}

/** Schema problems that make the loop state unreadable. */
function validateState(state) {
  if (!isObject(state)) return ['state must be a JSON object'];
  const errors = [];
  if (state.schemaVersion !== SCHEMA_VERSION) errors.push(`schemaVersion must be ${SCHEMA_VERSION}`);
  if (!Number.isInteger(state.issue) || state.issue < 1) errors.push('issue must be a positive Issue number');
  if (state.maxRounds !== undefined && (!Number.isInteger(state.maxRounds) || state.maxRounds < 1)) {
    errors.push('maxRounds must be a positive integer');
  }
  if (!Array.isArray(state.rounds)) errors.push('rounds must be an array');
  else {
    for (const [index, round] of state.rounds.entries()) {
      if (!isObject(round) || !isText(round.verdict)) errors.push(`rounds[${index}].verdict must be a verdict file path`);
    }
  }
  if (!Array.isArray(state.questions)) errors.push('questions must be an array');
  else {
    const ids = new Set();
    const rounds = Array.isArray(state.rounds) ? state.rounds.length : 0;
    for (const [index, question] of state.questions.entries()) {
      const where = `questions[${index}]`;
      if (!isObject(question)) { errors.push(`${where} must be an object`); continue; }
      if (!isText(question.id)) errors.push(`${where}.id is required`);
      else if (ids.has(question.id)) errors.push(`questions id ${question.id} is duplicated`);
      else ids.add(question.id);
      if (!isText(question.text)) errors.push(`${where}.text is required`);
      if (typeof question.material !== 'boolean') errors.push(`${where}.material must be true or false`);
      if (!QUESTION_STATUSES.has(question.status)) errors.push(`${where}.status must be open or answered`);
      // A material answer can change the criteria, so the loop must know which rounds ran before it.
      const after = question.answeredAfterRound;
      if (question.material && question.status === 'answered' && (!Number.isInteger(after) || after < 0 || after > rounds)) {
        errors.push(`${where}.answeredAfterRound must be the number of rounds recorded when the answer arrived (0-${rounds})`);
      }
    }
  }
  return errors;
}

/**
 * The next step of the loop. Gating uses the rules of verification-verdict.mjs, so this decides only what the loop
 * does with a verdict: publish a pass for the commit to push, commit a pass for the worktree, fix a fail, re-verify a
 * changed candidate, and stop rather than loop when the verdict is blocked or untrustworthy, a failed candidate is
 * retried, or the round budget is spent.
 */
function decide(state, stateDir, options) {
  const maxRounds = state.maxRounds ?? DEFAULT_MAX_ROUNDS;
  const rounds = state.rounds.length;
  const next = (action, reason) => ({ issue: state.issue, action, reason, rounds, maxRounds });

  const open = state.questions.filter(question => question.material && question.status === 'open');
  if (open.length) {
    return next('ask', `material question(s) ${open.map(question => question.id).join(', ')} are open; ask the human and wait before publishing`);
  }
  if (!rounds) return next('verify', 'no independent verification has run yet');
  if (rounds > maxRounds) return next('stop', `${rounds} rounds exceed the round budget of ${maxRounds}; report the remaining blockers`);

  const verdicts = state.rounds.map(round => readJson(resolve(stateDir, round.verdict), 'verdict'));
  for (const [index, verdict] of verdicts.entries()) {
    const errors = validateVerdict(verdict);
    if (errors.length) return next('stop', `round ${index + 1} verdict is invalid: ${errors.join('; ')}`);
    if (verdict.issue !== state.issue) return next('stop', `round ${index + 1} verdict is for Issue #${verdict.issue}, not Issue #${state.issue}`);
  }

  const answered = state.questions.filter(question => question.material && question.answeredAfterRound >= rounds);
  if (answered.length) {
    return next('verify', `material question(s) ${answered.map(question => question.id).join(', ')} were answered after round ${rounds}; verify against the updated criteria`);
  }

  const latest = verdicts.at(-1);
  // Only a failed candidate is final: a blocked one may pass once the missing capability is legitimately obtained, and
  // a material answer changes the criteria, so failures judged before the latest answer no longer count.
  const answeredAfter = Math.max(0, ...state.questions
    .filter(question => question.material && question.status === 'answered').map(question => question.answeredAfterRound));
  const failedRound = (candidate, before = rounds) => verdicts.findIndex((verdict, index) =>
    index >= answeredAfter && index < before && verdict.verdict === 'fail' && sameCandidate(verdict.candidate, candidate)) + 1;
  const repeated = failedRound(latest.candidate, rounds - 1);
  if (repeated) {
    return next('stop', `round ${rounds} verified the same candidate as failed round ${repeated}; change the candidate or report the blocker instead of retrying`);
  }

  // The commit to push is cheap to identify; the worktree identity is built only when it is needed, and at most once.
  const baseRef = options.get('--base') ?? DEFAULT_BASE_REF;
  const rev = options.get('--commit');
  const commit = rev ? commitIdentity(process.cwd(), rev, baseRef) : undefined;
  const onCommit = commit && sameCandidate(commit, latest.candidate);
  const worktree = onCommit ? undefined : candidateIdentity(process.cwd(), baseRef);
  const budgetLeft = rounds < maxRounds;

  if (!onCommit && !sameCandidate(worktree, latest.candidate)) {
    const known = failedRound(worktree);
    if (!budgetLeft) return next('stop', `the candidate changed after the last verdict and the round budget of ${maxRounds} is spent`);
    if (known) return next('fix', `the worktree is identical to failed round ${known}; fix its blockers before verifying again`);
    return next('verify', `the candidate changed after the last verdict; request a fresh independent verification. STALE: verdict is for ${describe(latest.candidate)}, but the worktree is ${describe(worktree)}`);
  }

  const { result, reasons } = computeVerdict(latest);
  const subject = `Issue #${latest.issue} candidate ${describe(latest.candidate)}`;
  if (latest.verdict !== result) {
    return next('stop', `the latest verdict cannot be trusted. INCONSISTENT: declared ${latest.verdict}, but the evidence supports ${result} for ${subject}`);
  }
  if (result === 'pass') {
    if (onCommit) return next('publish', `PASS: ${subject}: ${latest.criteria.length} criteria verified; checked against commit ${rev} (${commit.head.slice(0, 12)})`);
    return next('commit', `PASS for the worktree: ${subject}; commit exactly this content, then check again with --commit <rev to push>${rev ? `. Commit ${rev} does not hold it` : ''}`);
  }
  if (result === 'blocked') return next('stop', `verification is blocked; report what could not be verified: ${reasons.join('; ')}`);
  if (!budgetLeft) return next('stop', `round budget of ${maxRounds} is spent without a pass; report the remaining blockers: ${reasons.join('; ')}`);
  return next('fix', `fix the blockers, then request a fresh independent verification: ${reasons.join('; ')}`);
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const file = resolve(options.get('--state'));
  const state = readJson(file, 'loop state');
  const errors = validateState(state);
  if (errors.length) {
    console.error(`INVALID: loop state does not match schema version ${SCHEMA_VERSION}:`);
    for (const error of errors) console.error(`- ${error}`);
    process.exitCode = 2;
    return;
  }
  const decision = decide(state, dirname(file), options);
  console.log(JSON.stringify(decision, null, 2));
  process.exitCode = EXIT[decision.action];
}

try {
  main();
} catch (error) {
  console.error(error instanceof UsageError ? error.message : `ERROR: ${error.message}`);
  process.exitCode = 2;
}
