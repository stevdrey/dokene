#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const SCHEMA_VERSION = 1;
const DEFAULT_MAX_ROUNDS = 3;
const VALIDATOR = new URL('./verification-verdict.mjs', import.meta.url).pathname;
const QUESTION_STATUSES = new Set(['open', 'answered']);

// Exit codes: the loop may publish, should continue with the printed action, must stop, or could not decide.
const EXIT = { publish: 0, ask: 1, verify: 1, fix: 1, commit: 1, stop: 3 };

const USAGE = `Usage:
  issue-loop.mjs --state <file> [--commit <rev>] [--base <ref>]`;

class UsageError extends Error {}

const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const isText = value => typeof value === 'string' && value.trim() !== '';

/** Strict options, as in verification-verdict.mjs: `--name value` or `--name=value`, each at most once. */
function parseArgs(args) {
  const known = new Set(['--state', '--commit', '--base']);
  const options = new Map();
  for (let i = 0; i < args.length; i++) {
    const [name, inline] = args[i].startsWith('--') && args[i].includes('=')
      ? [args[i].slice(0, args[i].indexOf('=')), args[i].slice(args[i].indexOf('=') + 1)]
      : [args[i], undefined];
    if (!known.has(name)) throw new UsageError(`unknown argument ${args[i]}\n${USAGE}`);
    if (options.has(name)) throw new UsageError(`${name} given more than once`);
    const value = inline ?? args[++i];
    if (!value) throw new UsageError(`${name} requires a value`);
    // Refs reach Git, which would read a leading '-' as an option; the state path is only read as a file.
    if (name !== '--state' && value.startsWith('-')) throw new UsageError(`${name} value may not start with '-'`);
    options.set(name, value);
  }
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
  if (!Array.isArray(state.questions)) errors.push('questions must be an array');
  else {
    const ids = new Set();
    for (const [index, question] of state.questions.entries()) {
      const where = `questions[${index}]`;
      if (!isObject(question)) { errors.push(`${where} must be an object`); continue; }
      if (!isText(question.id)) errors.push(`${where}.id is required`);
      else if (ids.has(question.id)) errors.push(`questions id ${question.id} is duplicated`);
      else ids.add(question.id);
      if (!isText(question.text)) errors.push(`${where}.text is required`);
      if (typeof question.material !== 'boolean') errors.push(`${where}.material must be true or false`);
      if (!QUESTION_STATUSES.has(question.status)) errors.push(`${where}.status must be open or answered`);
    }
  }
  if (!Array.isArray(state.rounds)) errors.push('rounds must be an array');
  else {
    for (const [index, round] of state.rounds.entries()) {
      if (!isObject(round) || !isText(round.verdict)) errors.push(`rounds[${index}].verdict must be a verdict file path`);
    }
  }
  return errors;
}

function validate(verdictFile, options) {
  const args = [VALIDATOR, '--verdict', verdictFile];
  if (options.has('--commit')) args.push('--commit', options.get('--commit'));
  if (options.has('--base')) args.push('--base', options.get('--base'));
  const result = spawnSync(process.execPath, args, { encoding: 'utf8', timeout: 30_000 });
  if (result.error) throw result.error;
  return { status: result.status, output: `${result.stdout}${result.stderr}`.trim() };
}

const sameCandidate = (a, b) => isObject(a) && isObject(b) && a.base === b.base && a.tree === b.tree;
const firstLine = text => text.split('\n')[0];

/**
 * The next step of the loop. Gating is delegated to verification-verdict.mjs, so this decides only what the loop does
 * with a verdict: publish a pass, fix a fail, re-verify a changed candidate, and stop rather than loop when the verdict
 * is blocked, untrustworthy, a retry of an unchanged candidate, or the round budget is spent.
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

  const files = state.rounds.map(round => resolve(stateDir, round.verdict));
  const verdicts = files.map(file => readJson(file, 'verdict'));
  const latest = verdicts.at(-1);
  if (isObject(latest) && latest.issue !== state.issue) {
    return next('stop', `the latest verdict is for Issue #${latest.issue}, not Issue #${state.issue}`);
  }
  // Only a failed candidate is final: a blocked one may pass once the missing capability is legitimately obtained.
  const repeated = verdicts.slice(0, -1).findIndex(earlier =>
    earlier?.verdict === 'fail' && sameCandidate(earlier?.candidate, latest?.candidate));
  if (repeated !== -1) {
    return next('stop', `round ${rounds} verified the same candidate as failed round ${repeated + 1}; change the candidate or report the blocker instead of retrying`);
  }

  const { status, output } = validate(files.at(-1), options);
  const budgetLeft = rounds < maxRounds;
  if (status === 0) return next('publish', firstLine(output));
  if (status === 1) {
    if (latest.verdict === 'blocked') return next('stop', `verification is blocked; report what could not be verified. ${firstLine(output)}`);
    if (!budgetLeft) return next('stop', `round budget of ${maxRounds} is spent without a pass; report the remaining blockers`);
    return next('fix', `fix the blockers, then request a fresh independent verification. ${firstLine(output)}`);
  }
  if (output.startsWith('STALE:')) {
    if (options.has('--commit')) {
      const worktree = validate(files.at(-1), new Map([...options].filter(([name]) => name !== '--commit')));
      if (worktree.status === 0) {
        return next('commit', `the verified content is in the worktree but not in commit ${options.get('--commit')}; commit exactly that content, then check again`);
      }
    }
    if (!budgetLeft) return next('stop', `the candidate changed after the last verdict and the round budget of ${maxRounds} is spent`);
    return next('verify', `the candidate changed after the last verdict; request a fresh independent verification. ${firstLine(output)}`);
  }
  if (output.startsWith('INVALID:') || output.startsWith('INCONSISTENT:')) {
    return next('stop', `the latest verdict cannot be trusted; ${firstLine(output)}`);
  }
  throw new Error(output || `verification-verdict.mjs exited with ${status}`);
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
