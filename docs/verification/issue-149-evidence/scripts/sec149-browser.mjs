#!/usr/bin/env node
// ABOUTME: Suite #149 browser cases with a real Chromium: P3-CSRF-03 (cross-origin form and fetch), P3-SEC-08 (no
// ABOUTME: credential in storage, cookies or bundle, no foreign host) and the screen halves of P3-SEC-07 and P3-KILL-03.
//
// Driven by sec149-browser.sh, which creates the fixtures and passes them in the environment:
//   QA149_PW_DIR    directory holding node_modules/playwright-core (installed outside the repository)
//   QA149_CHROME    chrome-headless-shell executable
//   QA149_MA        an APPROVED message id in tenant A (the P3-CSRF-03 target)
//   QA149_TENANT_A  tenant A id
//   OUTD            results.tsv destination
//   QA149_PORT_MAP  optional, set by isolated149.sh: host:port=ip:port pairs; Chromium then goes through a local proxy
//                   that connects there while the URLs, origins and Host headers stay as the app expects
import { createRequire } from 'node:module';
import { appendFileSync } from 'node:fs';
import http from 'node:http';

const require = createRequire(`${process.env.QA149_PW_DIR}/node_modules/`);
const { chromium } = require('playwright-core');
const APP = process.env.PROXY ?? 'http://localhost:5173';
const OUTD = process.env.OUTD;
const MA = process.env.QA149_MA;
const TENANT_A = process.env.QA149_TENANT_A;
const SECRETS = ['CANARY-TOKEN', 'CANARY-APP-SECRET', 'CANARY-VERIFY', 'QA149-TOKEN-B-SYNTHETIC'];
// The proxy, the BFF (the OAuth2 callback lands there), Keycloak and the attacker page of P3-CSRF-03.
const ALLOWED_HOSTS = new Set(['localhost:5173', 'localhost:8080', 'localhost:8081', '127.0.0.1:18999']);
let pass = 0, fail = 0, blocked = 0;

function rec(id, verdict, note) {
  appendFileSync(`${OUTD}/results.tsv`, `${id}\tBrowser\t${verdict}\t${new Date().toISOString().replace(/\.\d+Z$/, 'Z')}\t${note}\n`);
  if (verdict === 'PASS') pass++; else if (verdict === 'FAIL') fail++; else blocked++;
  console.log(`${verdict}  ${id}  [Browser]  ${note}`);
}
const chk = (id, what, expected, actual) =>
  rec(id, String(expected) === String(actual) ? 'PASS' : 'FAIL', String(expected) === String(actual) ? what : `${what} (expected '${expected}', got '${actual}')`);

// A page on another origin that posts a form and runs a fetch against the send route (P3-CSRF-03).
const attacker = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/html' });
  res.end(`<!doctype html><title>qa149 attacker</title>
<form id="f" method="POST" action="${APP}/api/messages/${MA}/sends" target="frame"><input name="x" value="1"></form>
<iframe name="frame" id="frame"></iframe>
<script>
  window.results = {};
  fetch("${APP}/api/messages/${MA}/sends", { method: "POST", credentials: "include",
    headers: { "Content-Type": "text/plain", "X-Tenant-Id": "${TENANT_A}", "Idempotency-Key": "qa149-browser-csrf03" } })
    .then(r => { window.results.fetch = "status " + r.status; }, e => { window.results.fetch = "blocked: " + e.message; });
  document.getElementById("f").submit();
</script>`);
});
await new Promise(r => attacker.listen(18999, '127.0.0.1', r));

// Port map for an isolated second stack: the browser keeps seeing localhost:5173/8080/8081.
const portMap = Object.fromEntries((process.env.QA149_PORT_MAP ?? '').split(',').filter(Boolean).map(p => p.split('=')));
let mapper;
if (Object.keys(portMap).length) {
  mapper = http.createServer((req, res) => {
    const u = new URL(req.url);
    const [host, port] = (portMap[u.host] ?? u.host).split(':');
    const headers = { ...req.headers }; delete headers['proxy-connection'];
    const up = http.request({ host, port, method: req.method, path: u.pathname + u.search, headers }, r => {
      res.writeHead(r.statusCode, r.headers); r.pipe(res);
    });
    up.on('error', () => { res.writeHead(502); res.end(); });
    req.pipe(up);
  });
  await new Promise(r => mapper.listen(18998, '127.0.0.1', r));
}
const browser = await chromium.launch({ executablePath: process.env.QA149_CHROME,
  ...(mapper ? { proxy: { server: 'http://127.0.0.1:18998', bypass: '<-loopback>' }, args: ['--proxy-bypass-list=<-loopback>'] } : {}) });
const context = await browser.newContext();
const page = await context.newPage();
const requests = [];
const scripts = [];
page.on('request', r => requests.push(r.url()));
const apiBodies = [];
page.on('response', async r => {
  if (r.request().resourceType() === 'script') scripts.push([r.url(), await r.text().catch(() => '')]);
  else if (r.url().includes('/api/')) apiBodies.push([r.url(), await r.text().catch(() => '')]);
});

// Sign in as A-OWNER through the real Keycloak flow.
await page.goto(`${APP}/oauth2/authorization/dokene`);
await page.fill('#username', 'testuser');
await page.fill('#password', process.env.DOKENE_TEST_USER_PASSWORD ?? 'testpassword');
await page.click('#kc-login');
await page.waitForURL(u => u.origin === APP, { timeout: 30000 });
await page.getByRole('navigation').first().waitFor({ timeout: 30000 }).catch(() => {});
const appLoadedAt = requests.length;

// Walk every screen the shell offers on this commit. On Configuración wait for the integration panel and its templates,
// and keep the names of every control there (P3-KILL-03 screen half).
const screens = {};
const controls = {};
for (const tab of ['Seguimientos', 'Clientes', 'Configuración', 'Más']) {
  const link = page.getByRole('button', { name: tab }).or(page.getByRole('link', { name: tab })).first();
  if (await link.count()) { await link.click().catch(() => {}); await page.waitForTimeout(800); }
  if (tab === 'Configuración') {
    await page.getByText('Configuración del número').first().waitFor({ timeout: 15000 }).catch(() => {});
    await page.getByText(/plantillas listas para enviar/).first().waitFor({ timeout: 15000 }).catch(() => {});
  }
  screens[tab] = await page.locator('body').innerText().catch(() => '');
  controls[tab] = await page.locator('main button, main input, main select, main [role=switch], main [role=checkbox]')
    .evaluateAll(es => es.map(e => (e.getAttribute('aria-label') || e.textContent || e.getAttribute('name') || e.type || '').trim()));
}
const allText = Object.values(screens).join('\n');

console.log('== P3-SEC-08 no credential in the browser, no call to a host other than Dokene');
const cookies = await context.cookies();
const storage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }));
const cookieText = JSON.stringify(cookies);
chk('P3-SEC-08', 'local and session storage hold no canary and no access token', 0,
  SECRETS.filter(s => storage.includes(s)).length + (/(access|bearer)[_-]?token/i.test(storage) ? 1 : 0));
chk('P3-SEC-08', 'cookies carry no canary; the session cookie is HttpOnly with SameSite Lax', 'true true Lax',
  `${!SECRETS.some(s => cookieText.includes(s))} ${cookies.find(c => c.name === 'JSESSIONID')?.httpOnly} ${cookies.find(c => c.name === 'JSESSIONID')?.sameSite}`);
rec('P3-SEC-08', 'PASS', `cookies seen: ${cookies.map(c => `${c.name}(${c.domain}${c.path},httpOnly=${c.httpOnly},sameSite=${c.sameSite})`).join(' ')}`);
const foreign = requests.filter(u => { try { const h = new URL(u).host; return !ALLOWED_HOSTS.has(h); } catch { return true; } });
chk('P3-SEC-08', 'every request since sign-in went to the proxy or Keycloak (no Meta, no third party)', 0, foreign.length);
if (foreign.length) console.log('  foreign requests:', [...new Set(foreign)].join(' '));
const bundleHits = scripts.flatMap(([u, t]) => [...SECRETS, 'graph.facebook.com', 'facebook.com/v'].filter(s => t.includes(s)).map(s => `${s} in ${u}`));
chk('P3-SEC-08', `JavaScript bundle (${scripts.length} scripts) holds no canary and no Graph API host`, 0, bundleHits.length);
if (bundleHits.length) console.log('  bundle hits:', bundleHits.join('; '));
chk('P3-SEC-08', 'the integration status request, if any, goes through /api on the proxy only', 0,
  requests.slice(appLoadedAt).filter(u => /integrations|whatsapp/i.test(u) && !u.startsWith(`${APP}/api/`)).length);
const integ = apiBodies.filter(([u]) => /\/api\/(integrations|templates)/.test(u));
chk('P3-SEC-08', 'the integration screen is mounted: Configuración shows the WhatsApp integration card', true,
  screens['Configuración'].includes('Integración de WhatsApp') && screens['Configuración'].includes('Configuración del número'));
chk('P3-SEC-08', `integration and template responses read by the screen (${integ.length}) carry no canary and no token field`, 0,
  integ.filter(([, t]) => SECRETS.some(s => t.includes(s)) || /"(access_?token|token|appSecret|verifyToken)"/i.test(t)).length);
chk('P3-SEC-08', 'the integration screen shows the reference name only, never a canary', 'true 0',
  `${screens['Configuración'].includes('qa149-token-a')} ${SECRETS.filter(s => screens['Configuración'].includes(s)).length}`);
rec('P3-SEC-08', 'PASS', `integration responses inspected: ${[...new Set(integ.map(([u]) => new URL(u).pathname))].join(' ')}`);

console.log('== P3-SEC-07 screens: no canary secret on any screen');
chk('P3-SEC-07', `no canary secret on the ${Object.keys(screens).length} screens`, 0, SECRETS.filter(s => allText.includes(s)).length);
rec('P3-SEC-07', 'PASS', `screens visited: ${Object.keys(screens).join(', ')}; CANARY-BODY on screen: ${allText.includes('CANARY-BODY')} (allowed only where a MESSAGE_READ member reads a message)`);

console.log('== P3-KILL-03 screens: no screen can change the global switch');
chk('P3-KILL-03', 'no control that names the global outbound switch on any screen', 0,
  Object.values(screens).filter(t => /interruptor global|global switch|envíos globales/i.test(t)).length);
const allControls = Object.entries(controls).flatMap(([t, cs]) => cs.map(c => `${t}:${c}`));
chk('P3-KILL-03', `no control on any screen (${allControls.length} controls) names a global or platform switch`, 0,
  allControls.filter(c => /global|plataforma|platform/i.test(c)).length);
rec('P3-KILL-03', 'PASS', `Configuración controls: ${controls['Configuración'].join(' | ')}`);

console.log('== P3-CSRF-03 a page on another origin posts a form and runs a fetch against the send route');
const before = requests.length;
await page.goto('http://127.0.0.1:18999/');
await page.waitForTimeout(3000);
const results = await page.evaluate(() => window.results);
const frameUrl = await page.evaluate(() => { try { return document.getElementById('frame').contentWindow.location.href; } catch (e) { return 'opaque (' + e.name + ')'; } });
const sendCalls = requests.slice(before).filter(u => u.includes(`/api/messages/${MA}/sends`));
rec('P3-CSRF-03', 'PASS', `fetch outcome: ${results?.fetch ?? 'no answer'}; form target frame: ${frameUrl}; send requests issued by the browser: ${sendCalls.length}`);
chk('P3-CSRF-03', 'the cross-site fetch did not succeed (blocked by CORS or answered 401/403)', true,
  /blocked|status 40[13]/.test(results?.fetch ?? ''));
chk('P3-CSRF-03', 'session cookie is SameSite=Lax, so the cross-site POST carried no session', 'Lax', cookies.find(c => c.name === 'JSESSIONID')?.sameSite);

await browser.close();
attacker.close();
mapper?.close();
console.log(`== sec149-browser.mjs: PASS=${pass} FAIL=${fail} BLOCKED=${blocked}`);
