// ABOUTME: Suite #338 UX-05 operator recovery in a real browser on the local stack: loader under latency, double
// ABOUTME: activation, lost response after commit, offline, ambiguous 500, parallel 409, ended session, revoked member, 429.
//   QA_PW=... QA_PW_OP=... node ux05-338.mjs <chromium|firefox>
// Network faults are injected in the browser (Playwright routing / offline mode) and labelled as such; the server state
// before and after each fault is read from the database by db338.sh.
import { execFileSync } from 'child_process';
import { launch, login, save, shot, APP } from './pw338.mjs';

const BROWSER = process.argv[2] ?? 'chromium';
// SUF/IDSUF select a parallel fixture set (fixtures338-ux05.sh with the same suffixes) for a rerun in another browser.
const SUF = process.env.SUF ?? '', IDSUF = process.env.IDSUF ?? '';
const LAT_CUSTOMER = (process.env.LAT_CUSTOMER ?? 'QA338 Latencia') + SUF;
const LAT_ID = (process.env.LAT_ID ?? 'H1M') + IDSUF;
const HERE = new URL('.', import.meta.url).pathname;
const sh = (...a) => execFileSync('bash', [`${HERE}${a[0]}`, ...a.slice(1)], { encoding: 'utf8' }).trim();
const ids = Object.fromEntries(sh('db338.sh', 'ids').split('\n').filter(Boolean).map(l => l.split('=')));
const db = m => sh('db338.sh', 'msg', m);
const checks = [];
const check = (c, name, ok, note = '') => { checks.push({ case: c, name, result: ok ? 'PASS' : 'FAIL', note }); console.log(`${ok ? 'PASS' : 'FAIL'}  [${c}] ${name}${note ? '  (' + note + ')' : ''}`); };
const visible = async loc => (await loc.count()) > 0 && (await loc.first().isVisible());

async function openReview(page, customer) {
  const back = page.getByRole('button', { name: 'Volver a Seguimientos' });
  if (await visible(back)) await back.click();
  await page.getByRole('heading', { name: customer, level: 3 }).click();
  const row = page.getByRole('button', { name: /^Aún no enviado Seguimiento/ }).first();
  await row.waitFor({ timeout: 15000 });
  if ((await row.getAttribute('aria-expanded')) !== 'true') await row.click();
  await page.getByRole('button', { name: 'Abrir en revisión' }).first().click();
  await page.getByRole('heading', { name: 'Lo que recibirá el cliente' }).waitFor({ timeout: 15000 });
}
const alertText = async page => (await page.getByRole('alert').allInnerTexts()).join(' | ').replace(/\s+/g, ' ');
const statusText = async page => (await page.getByRole('status').allInnerTexts()).filter(Boolean).join(' | ').replace(/\s+/g, ' ');
const approvals = page => { const urls = []; page.on('request', r => { if (r.method() === 'POST' && /\/approvals$/.test(r.url())) urls.push(r.headers()['idempotency-key']); }); return urls; };

const browser = await launch(BROWSER);
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'es-CL' });
const page = await ctx.newPage();
const sentKeys = approvals(page);
await login(page, 'testuser', process.env.QA_PW);

try {
  // --- Latency: a slow message read shows a loader; a slow approval keeps the confirm button busy, and a second
  // activation during the wait sends nothing.
  let C = 'UX-05 latency';
  if (!process.env.SKIP_LAT) {
  await page.route('**/api/messages/*', async r => { if (r.request().method() === 'GET') await new Promise(x => setTimeout(x, 2500)); await r.continue(); });
  await page.getByRole('heading', { name: LAT_CUSTOMER, level: 3 }).click();
  const row = page.getByRole('button', { name: /^Aún no enviado Seguimiento/ }).first();
  await row.click();
  await page.getByRole('button', { name: 'Abrir en revisión' }).first().click();
  const loader = page.getByText('Cargando mensaje…');
  check(C, 'a slow message read shows the "Cargando mensaje…" loader (aria-busy region)', await loader.first().isVisible({ timeout: 2000 }).catch(() => false), await page.locator('[aria-busy=true]').count() + ' busy region(s)');
  await page.getByRole('heading', { name: 'Lo que recibirá el cliente' }).waitFor({ timeout: 15000 });
  await page.unroute('**/api/messages/*');
  await page.route('**/approvals', async r => { await new Promise(x => setTimeout(x, 3000)); await r.continue(); });
  const before = db(ids[LAT_ID]);
  await page.getByRole('button', { name: 'Aprobar', exact: true }).click();
  // The busy button relabels itself "Cargando...", so keep a handle to the same element.
  const confirm = await page.getByRole('button', { name: 'Confirmar aprobación' }).elementHandle();
  const n0 = sentKeys.length;
  await confirm.click();
  await page.waitForTimeout(300);
  const busy = await confirm.getAttribute('aria-disabled');
  const busyLabel = (await confirm.innerText()).trim();
  await confirm.click({ force: true }); await page.keyboard.press('Enter'); await confirm.click({ force: true });
  await page.getByText('Mensaje aprobado. Aún no se ha enviado.').waitFor({ timeout: 15000 });
  await page.unroute('**/approvals');
  const after = db(ids[LAT_ID]);
  check(C, 'while the approval is in flight the confirm button is aria-disabled and repeated activation sends no second request', busy === 'true' && sentKeys.length - n0 === 1 && /approvals=1\//.test(after), `label "${busyLabel}", aria-disabled=${busy}, requests=${sentKeys.length - n0}, db ${before} -> ${after}`);

  }
  // --- Lost response after the server committed (interrupted approval), then a safe retry. Needs route.fetch(),
  // which the WebDriver BiDi Firefox channel of this Playwright build does not support (SKIP_FETCH=1 there).
  C = 'UX-05 interrupted approval';
  if (!process.env.SKIP_FETCH) {
  await openReview(page, process.env.INT_CUSTOMER ?? 'QA338 Red Interrumpida' + SUF);
  await page.route('**/approvals', async r => { await r.fetch(); await r.abort('connectionreset'); }, { times: 1 });
  await page.getByRole('button', { name: 'Aprobar', exact: true }).click();
  const k0 = sentKeys.length;
  await page.getByRole('button', { name: 'Confirmar aprobación' }).click();
  await page.getByText(/No se pudo confirmar la acción\.|La acción pudo haberse completado/).first().waitFor({ timeout: 15000 });
  const mid = db(ids[process.env.INT_ID ?? 'R1M' + IDSUF]);
  const a1 = await alertText(page);
  check(C, 'the server committed but the browser lost the response: the UI says it could not confirm (no success, no failure claim) and re-reads the server state (or offers Reintentar)', /APPROVED\/.*approvals=1/.test(mid) && /(No se pudo confirmar la acción|La acción pudo haberse completado)/.test(a1) && !/Mensaje aprobado/.test(await statusText(page)), `db ${mid}; alert "${a1}"`);
  await shot(page, `ux05-${BROWSER}-interrupted.png`);
  const retry = page.getByRole('button', { name: 'Reintentar' });
  const retried = await visible(retry);
  if (retried) { await retry.click(); await page.waitForTimeout(2500); }
  const fin = db(ids[process.env.INT_ID ?? 'R1M' + IDSUF]);
  const s1 = `${await statusText(page)} ${await alertText(page)}`;
  check(C, 'recovery leaves exactly one approval; any retry reuses the same Idempotency-Key; the review shows the real status and offers Enviar, not Aprobar', /approvals=1\//.test(fin) && sentKeys.slice(k0).every(k => k === sentKeys[k0]) && !(await visible(page.getByRole('button', { name: 'Aprobar', exact: true }))), `retry shown=${retried}; requests=${sentKeys.length - k0}; db ${fin}; ui "${s1}"`);

  }
  // --- Offline: the request never leaves the browser; the UI is uncertain; back online, the retry lands once.
  C = 'UX-05 offline';
  await openReview(page, 'QA338 Sesion Vencida' + SUF);
  await page.getByRole('button', { name: 'Aprobar', exact: true }).click();
  await ctx.setOffline(true);
  await page.getByRole('button', { name: 'Confirmar aprobación' }).click();
  await page.getByText(/No se pudo confirmar la acción\.|La acción pudo haberse completado/).first().waitFor({ timeout: 15000 });
  const off = db(ids['R3M' + IDSUF]);
  check(C, 'offline: the UI reports it could not confirm; the server is untouched (still pending)', /^PENDING_APPROVAL\/.*approvals=0/.test(off), `db ${off}; alert "${await alertText(page)}"`);
  await ctx.setOffline(false);
  await page.getByRole('button', { name: 'Reintentar' }).click();
  await page.waitForTimeout(2500);
  const on = db(ids['R3M' + IDSUF]);
  check(C, 'back online, Reintentar approves exactly once', /^APPROVED\/.*approvals=1\//.test(on), `db ${on}; ui "${await statusText(page)}"`);

  // --- Ambiguous 500 after commit: the server approved, the browser receives an uncoded 500 (route.fetch, see above).
  C = 'UX-05 500';
  if (!process.env.SKIP_FETCH) {
  await openReview(page, process.env.E5_CUSTOMER ?? 'QA338 Error Servidor' + SUF);
  await page.route('**/approvals', async r => { await r.fetch(); await r.fulfill({ status: 500, body: '' }); }, { times: 1 });
  await page.getByRole('button', { name: 'Aprobar', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmar aprobación' }).click();
  await page.getByText(/No se pudo confirmar la acción\.|La acción pudo haberse completado/).first().waitFor({ timeout: 15000 });
  const e5 = db(ids[process.env.E5_ID ?? 'E5M' + IDSUF]);
  check(C, 'an uncoded 500 after commit is treated as uncertain (no success, no "failed" claim)', /APPROVED\/.*approvals=1/.test(e5) && !/Mensaje aprobado/.test(await statusText(page)), `db ${e5}; alert "${await alertText(page)}"`);
  const retry5 = page.getByRole('button', { name: 'Reintentar' });
  if (await visible(retry5)) { await retry5.click(); await page.waitForTimeout(2500); }
  check(C, 'the recovery after the 500 reconciles to the stored result without a second approval', /approvals=1\//.test(db(ids[process.env.E5_ID ?? 'E5M' + IDSUF])), `db ${db(ids[process.env.E5_ID ?? 'E5M' + IDSUF])}; ui "${await statusText(page)} ${await alertText(page)}"`);

  }
  // --- 409: someone else approves while this operator has the review open.
  C = 'UX-05 409';
  await openReview(page, 'QA338 Conflicto Paralelo' + SUF);
  const other = sh('api338.sh', 'approve', 'testadmin', ids['R2M' + IDSUF]);
  await page.getByRole('button', { name: 'Aprobar', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmar aprobación' }).click();
  await page.waitForTimeout(2500);
  const ui409 = `${await alertText(page)} | ${await statusText(page)}`;
  const r2 = db(ids['R2M' + IDSUF]);
  check(C, 'a parallel approval makes the stale approve refuse (409) with "Este mensaje cambió" guidance; one approval only', /approvals=1\//.test(r2) && /cambió/.test(ui409), `${other}; db ${r2}; ui "${ui409}"`);
  await shot(page, `ux05-${BROWSER}-409.png`);

  // --- 429 (real): a burst of recommendation requests by the same person, then one from the browser.
  C = 'UX-05 429';
  const burst = sh('api338.sh', 'recburst', ids.T1, '21');
  await page.getByRole('heading', { name: /^QA338 Ñandú/, level: 3 }).click();
  await page.getByRole('button', { name: 'Obtener recomendación' }).click();
  await page.waitForTimeout(2000);
  const rl = (await page.getByRole('region', { name: 'Asistente IA' }).innerText()).replace(/\s+/g, ' ');
  check(C, 'the recommendation limiter answers 429 and the assistant explains the wait (no generic failure)', /429/.test(burst) && /(espera|demasiad|límite)/i.test(rl), `${burst.replace(/ 200/g, '')}; ui "${rl.slice(0, 220)}"`);
  await shot(page, `ux05-${BROWSER}-429.png`);

  // --- 429 for a send (injected in the browser: the tenant send limiter needs real sends).
  C = 'UX-05 429 send (injected)';
  await openReview(page, 'QA338 Vista Aprobada');
  await page.route('**/sends', r => r.fulfill({ status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '30' }, body: JSON.stringify({ type: 'about:blank', title: 'Too Many Requests', status: 429, code: 'RATE_LIMITED' }) }));
  const va0 = db(ids.VA);
  await page.getByRole('button', { name: 'Enviar', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmar envío' }).click();
  await page.waitForTimeout(1500);
  const sendConfirm = page.getByRole('button', { name: 'Confirmar envío' });
  const t429 = (await page.getByRole('group', { name: 'Confirmar envío' }).innerText()).replace(/\s+/g, ' ');
  check(C, 'a send 429 shows "Se alcanzó el límite de envíos. Espera N s." and locks the confirm button; nothing reached the server', /Se alcanzó el límite de envíos/.test(t429) && (await sendConfirm.getAttribute('aria-disabled')) === 'true' && db(ids.VA) === va0, `ui "${t429}"; db ${va0} -> ${db(ids.VA)}`);
  await page.unroute('**/sends');
  await page.getByRole('button', { name: 'Cerrar', exact: true }).click().catch(() => {});

  // --- 401: the session ends in another tab; the next action says so and changes nothing.
  C = 'UX-05 401';
  await openReview(page, 'QA338 Sesion Terminada' + SUF);
  const tab2 = await ctx.newPage();
  await tab2.goto(APP); await tab2.getByRole('button', { name: 'Cerrar sesión' }).first().click(); await tab2.waitForTimeout(1500); await tab2.close();
  const l0 = db(ids['L1M' + IDSUF]);
  await page.getByRole('button', { name: 'Aprobar', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmar aprobación' }).click();
  await page.waitForTimeout(2500);
  const t401 = `${await alertText(page)} | ${(await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 300)}`;
  // The ended-session sentence or the signed-out view itself, never a navigation label such as "Cerrar sesión".
  check(C, 'after logout in another tab, approving reports the ended session and the message is unchanged', /(Tu sesión ya no está activa|Tu sesión terminó|Iniciar sesión con OIDC)/.test(t401) && db(ids['L1M' + IDSUF]) === l0, `db ${l0} -> ${db(ids['L1M' + IDSUF])}; ui "${t401.slice(0, 260)}"`);
  await shot(page, `ux05-${BROWSER}-401.png`);
} catch (e) {
  check('UX-05', 'script completed', false, String(e).split('\n')[0]);
  await shot(page, `ux05-${BROWSER}-ERROR.png`);
}
await ctx.close();

// --- 403: an OPERATOR's membership is revoked while the review is open.
let revoked = false;
if (!process.env.SKIP_403) try {
  const C = 'UX-05 403';
  const c2 = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'es-CL' });
  const p2 = await c2.newPage();
  await login(p2, 'testoperator', process.env.QA_PW_OP);
  await openReview(p2, 'QA338 Sin Permiso' + SUF);
  const g0 = db(ids['G1M' + IDSUF]);
  const rv = sh('api338.sh', 'revoke', 'testoperator');
  revoked = /revoke 204/.test(rv);
  await p2.getByRole('button', { name: 'Aprobar', exact: true }).click();
  await p2.getByRole('button', { name: 'Confirmar aprobación' }).click();
  await p2.waitForTimeout(2500);
  const t403 = `${await alertText(p2)}`;
  check(C, 'a revoked OPERATOR gets "No tienes permiso para esta acción." and the message is unchanged', /No tienes permiso/.test(t403) && db(ids['G1M' + IDSUF]) === g0, `${rv}; db ${g0} -> ${db(ids['G1M' + IDSUF])}; ui "${t403}"`);
  await shot(p2, `ux05-${BROWSER}-403.png`);
  await c2.close();
} catch (e) {
  check('UX-05 403', 'script completed', false, String(e).split('\n')[0]);
} finally {
  // A failed browser step must never leave the shared OPERATOR revoked for the next run.
  if (revoked) {
    const re = sh('api338.sh', 'reactivate', 'testoperator');
    check('UX-05 403', 'cleanup: membership reactivated and accepted', /reactivate 200/.test(re) && /accept 200/.test(re), re.replace(/\n/g, ', '));
  }
}
save(`ux05-${BROWSER}${IDSUF}.json`, { browser: BROWSER, version: browser.version(), checks });
await browser.close();
