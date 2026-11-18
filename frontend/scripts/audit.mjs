// ABOUTME: CI dependency audit: fails on any high or critical npm advisory, except advisories listed in
// ABOUTME: audit-allowlist.json with a reason and an unexpired date. An expired or malformed entry fails the audit.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const BLOCKING = new Set(['high', 'critical']);
const today = new Date().toISOString().slice(0, 10);

const allowlist = JSON.parse(readFileSync(new URL('../audit-allowlist.json', import.meta.url), 'utf8'));
const allowed = new Map();
let failed = false;
for (const entry of allowlist.advisories ?? []) {
  if (!/^GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}$/.test(entry.id ?? '') || !entry.reason || !/^\d{4}-\d{2}-\d{2}$/.test(entry.expires ?? '')) {
    console.error(`audit-allowlist.json: invalid entry ${JSON.stringify(entry)} (needs id GHSA-…, reason, expires YYYY-MM-DD)`);
    failed = true;
  } else if (entry.expires < today) {
    console.error(`audit-allowlist.json: ${entry.id} expired on ${entry.expires}; re-assess it (upgrade, or renew with a new reason).`);
    failed = true;
  } else {
    allowed.set(entry.id, entry);
  }
}

// npm audit exits nonzero whenever it finds anything, so read its JSON report instead of its exit status.
const run = spawnSync('npm', ['audit', '--json'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
let report;
try {
  report = JSON.parse(run.stdout);
} catch {
  console.error(`npm audit did not return a JSON report (exit ${run.status}):\n${run.stderr}`);
  process.exit(1);
}
if (report.error) {
  console.error(`npm audit failed: ${JSON.stringify(report.error)}`);
  process.exit(1);
}

// Each advisory appears as an object in the `via` list of the package it affects; packages that only depend on a
// vulnerable package list names instead, and are covered by that package's advisory.
const advisories = new Map();
for (const vulnerability of Object.values(report.vulnerabilities ?? {})) {
  for (const via of vulnerability.via ?? []) {
    if (typeof via !== 'object' || !BLOCKING.has(via.severity)) continue;
    const id = (via.url ?? '').match(/GHSA-[a-z0-9-]+$/)?.[0] ?? `npm:${via.source}`;
    const item = advisories.get(id) ?? { id, severity: via.severity, title: via.title, url: via.url, packages: new Set() };
    item.packages.add(via.name);
    advisories.set(id, item);
  }
}

const used = new Set();
for (const advisory of advisories.values()) {
  const packages = [...advisory.packages].join(', ');
  if (allowed.has(advisory.id)) {
    used.add(advisory.id);
    console.log(`allowed until ${allowed.get(advisory.id).expires}: ${advisory.id} (${advisory.severity}) ${advisory.title} [${packages}]`);
  } else {
    console.error(`BLOCKING: ${advisory.id} (${advisory.severity}) ${advisory.title} [${packages}] ${advisory.url ?? ''}`);
    failed = true;
  }
}
for (const id of allowed.keys()) {
  if (!used.has(id)) console.log(`note: allowlisted ${id} is no longer reported; remove it from audit-allowlist.json.`);
}
console.log(failed ? 'audit: FAILED' : `audit: OK (${advisories.size} high/critical advisories, ${used.size} allowlisted)`);
process.exit(failed ? 1 : 0);
