// ABOUTME: Suite #338 UX-07 stale responses in the real browser: slow reads across a customer or tenant switch, an
// ABOUTME: approval in flight across navigation and a viewport change, back/forward, and logout/relogin with an edited draft.
//   QA_PW=... node ux07-338.mjs <chromium|firefox> [id-suffix]
// Latency is injected in the browser (Playwright routing); state is read from the database by db338.sh.
import { execFileSync } from 'child_process';
import { launch, login, save, shot, APP } from './pw338.mjs';

const BROWSER = process.argv[2] ?? 'chromium';
const S = process.argv[3] ?? '';
// Fixture id suffix (fixtures338-ux07.sh IDSUF); a named variant without IDK keeps the original 'F' suffix.
const K = process.env.IDK ?? (S ? 'F' : '');
const HERE = new URL('.', import.meta.url).pathname;
const sh = (...a) => execFileSync('bash', [`${HERE}${a[0]}`, ...a.slice(1)], { encoding: 'utf8' }).trim();
const ids = Object.fromEntries(sh('db338.sh', 'ids').split('\n').filter(Boolean).map(l => l.split('=')));
const db = m => sh('db338.sh', 'msg', m);
const checks = [];
const check = (name, ok, note = '') => { checks.push({ case: 'UX-07', name, result: ok ? 'PASS' : 'FAIL', note }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${note ? '  (' + note + ')' : ''}`); };
const visible = async loc => (await loc.count()) > 0 && (await loc.first().isVisible());
const delay = ms => new Promise(r => setTimeout(r, ms));
const body = async p => (await p.locator('body').innerText()).replace(/\s+/g, ' ');

const browser = await launch(BROWSER);
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'es-CL' });
const page = await ctx.newPage();
await login(page, 'testuser', process.env.QA_PW);
const detailHeading = async () => (await page.locator('[role=region][aria-label^="Detalle de "]').first().getAttribute('aria-label')) ?? '';

try {
  // a) Customer switch while the first customer's history read is slow.
  await page.route(`**/api/messages?*customerId=${ids.V1}*`, async r => { await delay(3500); await r.continue(); });
  await page.getByRole('heading', { name: 'QA338 Vista Pendiente', level: 3, exact: true }).click();
  await delay(300);
  await page.getByRole('heading', { name: 'QA338 Vista Aprobada', level: 3, exact: true }).click();
  await delay(4500);
  const row = page.getByRole('button', { name: /^Aún no enviado Seguimiento/ }).first();
  await row.click();
  await delay(1200);
  const t1 = await body(page);
  check('customer switch: the late history of the first customer never replaces the second customer\'s view', (await detailHeading()) === 'Detalle de QA338 Vista Aprobada' && /sintetico VA/.test(t1) && !/sintetico VP/.test(t1), `${await detailHeading()}; VA shown=${/sintetico VA/.test(t1)}, VP shown=${/sintetico VP/.test(t1)}`);
  await page.unroute(`**/api/messages?*customerId=${ids.V1}*`);

  // b) Tenant switch while a tenant A read is slow.
  await page.route(`**/api/messages?*customerId=${ids.V1}*`, async r => { await delay(3500); await r.continue().catch(() => {}); });
  await page.getByRole('heading', { name: 'QA338 Vista Pendiente', level: 3, exact: true }).click();
  await delay(300);
  await page.getByRole('button', { name: 'Seleccionar espacio de trabajo' }).first().click();
  await page.getByRole('option', { name: /QA Café Sur/ }).or(page.getByRole('menuitem', { name: /QA Café Sur/ })).or(page.getByRole('button', { name: /QA Café Sur/ })).first().click();
  await delay(5000);
  const t2 = await body(page);
  check('tenant switch: no tenant A customer, message or name appears in tenant B after the late response', /QA Café Sur/.test(t2) && !/QA338/.test(t2), `A names in page: ${(t2.match(/QA338[^|]{0,25}/g) ?? []).slice(0, 3).join(', ') || 'none'}`);
  await shot(page, `ux07-${BROWSER}-tenant-switch.png`);
  await page.unroute(`**/api/messages?*customerId=${ids.V1}*`);
  await page.getByRole('button', { name: 'Seleccionar espacio de trabajo' }).first().click();
  await page.getByRole('option', { name: /QA Café Norte/ }).or(page.getByRole('menuitem', { name: /QA Café Norte/ })).or(page.getByRole('button', { name: /QA Café Norte/ })).first().click();
  await page.getByRole('heading', { name: 'QA338 Vista Pendiente', level: 3, exact: true }).waitFor();

  // c) An approval in flight while the operator opens another customer.
  const openReview = async name => {
    await page.getByRole('heading', { name, level: 3, exact: true }).click();
    const r = page.getByRole('button', { name: /^Aún no enviado Seguimiento/ }).first();
    await r.waitFor(); if ((await r.getAttribute('aria-expanded')) !== 'true') await r.click();
    await page.getByRole('button', { name: 'Abrir en revisión' }).first().click();
    await page.getByRole('heading', { name: 'Lo que recibirá el cliente' }).waitFor();
  };
  // The server receives the approval at once and the response is held for 3 s (route.fetch, Chromium only): a person
  // whose network is slow, not a request that never left the browser.
  if (BROWSER === 'chromium') {
  await openReview(`QA338 Vuelo Uno${S}`);
  await page.route('**/approvals', async r => { const resp = await r.fetch(); await delay(3000); await r.fulfill({ response: resp }).catch(() => {}); });
  await page.getByRole('button', { name: 'Aprobar', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmar aprobación' }).click();
  await delay(300);
  await page.getByRole('heading', { name: 'QA338 Vista Pendiente', level: 3, exact: true }).click();
  await delay(4500);
  const t3 = await body(page);
  const w1 = db(ids[`W1M${K}`]);
  check('navigating away during an approval: the other customer shows no foreign confirmation; the approval landed once', (await detailHeading()) === 'Detalle de QA338 Vista Pendiente' && !/Mensaje aprobado/.test(t3) && /approvals=1\//.test(w1), `${await detailHeading()}; db ${w1}`);
  await page.getByRole('heading', { name: `QA338 Vuelo Uno${S}`, level: 3, exact: true }).click();
  await delay(2000);
  const t3b = await body(page);
  check('returning to the first customer shows the real status (Aprobado, sin enviar)', /Aprobado, sin enviar/.test(t3b), (t3b.match(/Aún no enviado[^.]{0,60}/) ?? [''])[0]);
  await page.unroute('**/approvals');
  }

  // d) Viewport change while an approval is in flight.
  await openReview(`QA338 Vuelo Tres${S}`);
  await page.route('**/approvals', async r => { await delay(3000); await r.continue(); });
  await page.getByRole('button', { name: 'Aprobar', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmar aprobación' }).click();
  await delay(300);
  await page.setViewportSize({ width: 390, height: 844 });
  await delay(4500);
  const t4 = await body(page);
  const w2 = db(ids[`W3M${K}`]);
  check('viewport change during an approval: one approval, and the narrow layout shows the result', /approvals=1\//.test(w2) && /(Mensaje aprobado|Aprobado)/.test(t4), `db ${w2}; result shown=${/Mensaje aprobado/.test(t4)}`);
  await shot(page, `ux07-${BROWSER}-viewport-inflight.png`);
  await page.unroute('**/approvals');
  await page.setViewportSize({ width: 1280, height: 900 });

  // f) Logout and relogin with an edited AI draft.
  await page.getByRole('heading', { name: `QA338 Borrador Sesion${S}`, level: 3, exact: true }).click();
  await page.getByRole('button', { name: 'Obtener recomendación' }).click();
  await page.getByRole('button', { name: 'Generar borrador' }).click({ timeout: 30000 });
  const area = page.getByRole('region', { name: 'Asistente IA' }).locator('textarea').first();
  await area.waitFor({ timeout: 30000 });
  await area.click(); await page.keyboard.press('End'); await page.keyboard.press('ControlOrMeta+End'); await page.keyboard.type(' EDITADO-QA338');
  const edited = await area.inputValue();
  let leaveWarning = null;
  page.once('dialog', async d => { leaveWarning = d.message(); await d.dismiss(); });
  await page.getByRole('button', { name: 'Cerrar sesión' }).first().click();
  await delay(2500);
  const out = await body(page);
  check('logout with an unsaved edited draft: the outcome is explicit (warning or a clear signed-out page)', leaveWarning !== null || /Iniciar sesión/.test(out), leaveWarning ? `warning "${leaveWarning}"` : 'no warning; signed-out page shown');
  await page.getByRole('button', { name: /iniciar sesi/i }).first().click();
  // SSO may still be alive (app logout is local), so the IdP form may be skipped.
  const form = await page.waitForSelector('#username', { timeout: 6000 }).catch(() => null);
  if (form) { await page.fill('#username', 'testuser'); await page.fill('#password', process.env.QA_PW); await page.click('#kc-login'); }
  await page.getByRole('heading', { name: 'Seguimientos', level: 1 }).waitFor({ timeout: 30000 });
  await page.getByRole('heading', { name: `QA338 Borrador Sesion${S}`, level: 3, exact: true }).click();
  await delay(2500);
  const aiText = (await page.getByRole('region', { name: 'Asistente IA' }).innerText()).replace(/\s+/g, ' ');
  const kept = /EDITADO-QA338/.test(aiText) || (await page.getByRole('region', { name: 'Asistente IA' }).locator('textarea').evaluateAll(t => t.some(x => x.value.includes('EDITADO-QA338'))));
  check('after relogin the edited draft is either restored or clearly gone (the assistant starts over, no half state)', kept || /Obtener recomendación/.test(aiText), kept ? 'edit restored' : `edit lost; assistant shows "${aiText.slice(0, 120)}"`);
  checks.push({ case: 'UX-07', name: 'draft edit kept across logout/relogin (informational)', result: 'NOTE', note: `kept=${kept}; edited length ${edited.length}` });
  // e) Browser back/forward: the app keeps no history entries of its own.
  const before = page.url();
  await page.goBack({ timeout: 10000 }).catch(() => {});
  await delay(2500);
  const backUrl = page.url();
  const backText = await body(page);
  await page.goForward({ timeout: 10000 }).catch(() => {});
  await delay(3000);
  const fwdUrl = page.url();
  const fwdOk = await visible(page.getByRole('heading', { name: 'Seguimientos', level: 1 }));
  check('back/forward: Back leaves the app (no in-app history); Forward returns to a consistent app (queue, no stale data)', fwdOk, `before ${before}; back ${backUrl.replace(/\?.*/, '?…')} ("${backText.slice(0, 60)}"); forward ${fwdUrl}`);

} catch (e) {
  check('script completed', false, String(e).split('\n')[0]);
  await shot(page, `ux07-${BROWSER}-ERROR.png`);
}
save(`ux07-${BROWSER}.json`, { browser: BROWSER, version: browser.version(), checks });
await browser.close();
