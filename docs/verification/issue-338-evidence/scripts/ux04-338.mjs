// ABOUTME: Suite #338 window W1 browser part. "send": real sends from the review (accepted; response lost after the
// ABOUTME: provider call; double activation under a slow provider). "copy": the Spanish status copy of every state.
//   QA_PW=... node ux04-338.mjs send            (Chromium; global switch ON, loopback stub only)
//   QA_PW=... node ux04-338.mjs copy <browser>
import { execFileSync } from 'child_process';
import fs from 'fs';
import { launch, login, save, shot } from './pw338.mjs';

const MODE = process.argv[2];
const BROWSER = MODE === 'send' ? 'chromium' : (process.argv[3] ?? 'chromium');
const HERE = new URL('.', import.meta.url).pathname;
const STUBDIR = `${process.env.HOME}/qa333/stub`;
const sh = (...a) => execFileSync('bash', [`${HERE}${a[0]}`, ...a.slice(1)], { encoding: 'utf8' }).trim();
const ids = Object.fromEntries(sh('db338.sh', 'ids').split('\n').filter(Boolean).map(l => l.split('=')));
const db = m => sh('db338.sh', 'msg', m);
const stubSends = () => fs.readFileSync(`${STUBDIR}/requests.log`, 'utf8').split('\n').filter(l => /messages/.test(l) && /POST/.test(l)).length;
const checks = [];
const check = (c, name, ok, note = '') => { checks.push({ case: c, name, result: ok ? 'PASS' : 'FAIL', note }); console.log(`${ok ? 'PASS' : 'FAIL'}  [${c}] ${name}${note ? '  (' + note + ')' : ''}`); };
const delay = ms => new Promise(r => setTimeout(r, ms));
const visible = async loc => (await loc.count()) > 0 && (await loc.first().isVisible());
const norm = t => t.replace(/\s+/g, ' ').trim();

const browser = await launch(BROWSER);
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'es-CL' });
const page = await ctx.newPage();
const sendKeys = []; page.on('request', r => { if (r.method() === 'POST' && /\/sends$/.test(r.url())) sendKeys.push(r.headers()['idempotency-key']); });
await login(page, 'testuser', process.env.QA_PW);

async function openCustomer(name) {
  await page.getByRole('heading', { name, level: 3 }).click();
  const row = page.getByRole('list', { name: 'Mensajes del cliente' }).getByRole('button').first();
  await row.waitFor({ timeout: 15000 });
  return row;
}
async function openReview(name) {
  const row = await openCustomer(name);
  if ((await row.getAttribute('aria-expanded')) !== 'true') await row.click();
  await page.getByRole('button', { name: 'Abrir en revisión' }).first().click();
  await page.getByRole('heading', { name: 'Lo que recibirá el cliente' }).waitFor({ timeout: 15000 });
}
const reviewText = async () => norm(await page.locator('section.message-review').filter({ hasText: /Revisión \d+/ }).first().innerText());

try {
  if (MODE === 'send') {
    // Accepted by the provider.
    let C = 'UX-04 accepted';
    let s0 = stubSends();
    await openReview('QA338 Estado Aceptado');
    await page.getByRole('button', { name: 'Enviar', exact: true }).click();
    await page.getByRole('button', { name: 'Confirmar envío' }).click();
    await page.getByText(/Envío procesado/).first().waitFor({ timeout: 20000 });
    const ra = await reviewText();
    check(C, 'a send the provider accepted reports "Envío procesado" with the current status; it never says delivered', !/Entregado|Leído/.test(ra) && /Envío procesado\. Estado actual/.test(ra), `${(ra.match(/Envío procesado[^.]*\.[^.]*\./) ?? [''])[0]}; badge/status text: ${(ra.match(/(Enviado|Aceptado por el proveedor)[^.]{0,40}/) ?? [''])[0]}; db ${db(ids.SAM)}; stub +${stubSends() - s0}`);
    await shot(page, 'ux04-send-accepted.png');

    // Response lost after the provider call (Chromium route.fetch, then a reset connection).
    C = 'UX-05 interrupted send';
    s0 = stubSends();
    await openReview('QA338 Envio Interrumpido');
    await page.route('**/sends', async r => { await r.fetch(); await r.abort('connectionreset'); }, { times: 1 });
    await page.getByRole('button', { name: 'Enviar', exact: true }).click();
    const k0 = sendKeys.length;
    await page.getByRole('button', { name: 'Confirmar envío' }).click();
    await page.getByText(/No se pudo confirmar la acción\.|La acción pudo haberse completado/).first().waitFor({ timeout: 20000 });
    const ri = await reviewText();
    const di = db(ids.SIM);
    check(C, 'the provider was called once and the browser lost the answer: the UI claims neither success nor failure', /attempts=1\//.test(di) && stubSends() - s0 === 1 && !/Envío procesado/.test(ri), `db ${di}; stub +${stubSends() - s0}; ui "${(ri.match(/(No se pudo confirmar la acción\.|La acción pudo haberse completado[^.]*\.)/) ?? [''])[0]}"`);
    await shot(page, 'ux04-send-interrupted.png');
    const retry = page.getByRole('button', { name: 'Reintentar' });
    const retried = await visible(retry);
    if (retried) { await retry.click(); await delay(3000); }
    const ri2 = await reviewText();
    const di2 = db(ids.SIM);
    check(C, 'recovery never makes a second provider request: same Idempotency-Key on any retry, one attempt, the real status shown, no Enviar offered', /attempts=1\//.test(di2) && stubSends() - s0 === 1 && sendKeys.slice(k0).every(k => k === sendKeys[k0]) && !(await visible(page.getByRole('button', { name: 'Enviar', exact: true }))), `retry shown=${retried}; requests=${sendKeys.length - k0}; db ${di2}; stub +${stubSends() - s0}; ui "${(ri2.match(/(Envío procesado[^.]*\.[^.]*\.|Este mensaje[^.]*\.|La acción pudo[^.]*\.)/) ?? [''])[0]}"`);
    await page.unroute('**/sends');

    // Double activation while the provider is slow (stub delay 3 s).
    C = 'UX-05 double send';
    s0 = stubSends();
    fs.writeFileSync(`${STUBDIR}/delay.ms`, '3000');
    await openReview('QA338 Envio Doble Clic');
    await page.getByRole('button', { name: 'Enviar', exact: true }).click();
    const conf = await page.getByRole('button', { name: 'Confirmar envío' }).elementHandle();
    const k1 = sendKeys.length;
    await conf.click(); await delay(200);
    await conf.click({ force: true }).catch(() => {}); await page.keyboard.press('Enter'); await conf.dblclick({ force: true }).catch(() => {});
    await page.getByText(/Envío procesado/).first().waitFor({ timeout: 20000 });
    fs.rmSync(`${STUBDIR}/delay.ms`, { force: true });
    const dx = db(ids.SXM);
    check(C, 'repeated activation during a slow send: one request, one attempt, one provider call', sendKeys.length - k1 === 1 && /attempts=1\//.test(dx) && stubSends() - s0 === 1, `requests=${sendKeys.length - k1}; db ${dx}; stub +${stubSends() - s0}`);
  } else {
    // The status copy of every state, in the history row and in the review.
    // [customer, label, history row must match, review must match (status badge and attempt outcome), never in either]
    const states = [
      ['QA338 Vista Pendiente', 'submitted', /Aún no enviado.*Pendiente de aprobación/, /Revisión \d+ Pendiente de aprobación/, /Entregado|Envío procesado|Aceptado/],
      ['QA338 Vista Aprobada', 'approved', /Aún no enviado.*Aprobado, sin enviar/, /Revisión \d+ Aprobado\b/, /Entregado|Aceptado/],
      ['QA338 Estado Aceptado', 'provider accepted', /Aceptado por el proveedor.*Aún sin confirmación de entrega/, /Revisión \d+ Enviado\b.*Intento \d+ · Aceptado/, /Entregado|Leído/],
      ['QA338 Estado Entregado', 'delivered', /Entregado.*Entregado al teléfono del cliente/, /Revisión \d+ Entregado\b.*Intento \d+ · Aceptado/, /Fallido|sin confirmar/i],
      ['QA338 Estado Leido', 'read', /Leído.*El cliente abrió el mensaje/, /Revisión \d+ Leído\b.*Intento \d+ · Aceptado/, /Fallido|sin confirmar/i],
      ['QA338 Estado Fallido', 'failed', /Fallido.*No se entregó al cliente/, /Revisión \d+ Fallido\b.*Intento \d+ · Falló definitivamente/, /Entregado|Aceptado por el proveedor/],
      ['QA338 Estado Sin Confirmar', 'uncertain', /Resultado sin confirmar.*No lo reenvíes/, /Revisión \d+ Enviando\b.*El proveedor no confirmó el envío.*Intento \d+ · Resultado desconocido|Revisión \d+ Enviando\b.*Intento \d+ · Resultado desconocido.*El proveedor no confirmó el envío/, /Entregado|Envío procesado/]
    ];
    for (const [name, label, want, reviewWant, never] of states) {
      const row = await openCustomer(name);
      if ((await row.getAttribute('aria-expanded')) !== 'true') await row.click();
      await delay(1200);
      const item = norm(await page.getByRole('list', { name: 'Mensajes del cliente' }).getByRole('listitem').first().innerText());
      const rowOk = want.test(item) && !never.test(item);
      await page.getByRole('button', { name: 'Abrir en revisión' }).first().click();
      // A sent message's review shows "Texto del mensaje" and "Resultado del envío" instead of the template preview.
      // Terminal messages (read, delivered…) have no action region at all.
      await page.locator('section.message-review').filter({ hasText: /Revisión \d+/ }).first().waitFor({ timeout: 15000 });
      const rv = await reviewText();
      const canSend = await visible(page.getByRole('button', { name: 'Enviar', exact: true }));
      const reviewOk = reviewWant.test(rv) && !never.test(rv) && (label === 'approved' ? canSend : !canSend);
      check('UX-04', `${label}: history and review copy are distinct and truthful`, rowOk && reviewOk,
        `history "${item.slice(0, 150)}"; review status "${(rv.match(/Revisión \d+ (\S+(?: de aprobación)?)/) ?? ['', ''])[1]}"; result "${(rv.match(/Intento \d+ · [^ ]+( [a-z]+)?/) ?? [''])[0]}"; notices "${(rv.match(/(El proveedor no confirmó[^.]*\.|No se entregó[^.]*\.|Aún sin confirmación[^.]*)/) ?? [''])[0]}"; Enviar offered=${canSend}`);
      await shot(page, `ux04-${BROWSER}-${label.replace(/ /g, '-')}.png`);
    }
  }
} catch (e) {
  check('UX-04', 'script completed', false, String(e).split('\n')[0]);
  await shot(page, `ux04-${BROWSER}-${MODE}-ERROR.png`);
  fs.rmSync(`${STUBDIR}/delay.ms`, { force: true });
}
save(`ux04-${MODE}-${BROWSER}.json`, { browser: BROWSER, version: browser.version(), checks });
await browser.close();
