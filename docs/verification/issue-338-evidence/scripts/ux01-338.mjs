// ABOUTME: Suite #338 UX-01/UX-02 viewport sweep in real Chromium and Firefox against the local stack: follow-up queue,
// ABOUTME: message history, review, approval confirmation, a real refused send (global switch off) and integration/template settings.
//   QA_PW=... node ux01-338.mjs <chromium|firefox> [viewport-filter]
import { launch, login, layoutProbe, reachProbe, save, shot } from './pw338.mjs';

const BROWSER = process.argv[2] ?? 'chromium';
const FILTER = process.argv[3] ?? '';
const VIEWPORTS = [
  { id: '1440x900', viewport: { width: 1440, height: 900 } },
  { id: '1024x768', viewport: { width: 1024, height: 768 } },
  { id: '768x1024', viewport: { width: 768, height: 1024 } },
  { id: '390x844', viewport: { width: 390, height: 844 } },
  { id: '320x568', viewport: { width: 320, height: 568 } },
  // 200 % zoom of a 1440x900 window: half the CSS pixels at twice the device pixel ratio.
  { id: 'zoom200-1440x900', viewport: { width: 720, height: 450 }, deviceScaleFactor: 2 },
  // 400 % zoom of a 1280x1024 window (WCAG 1.4.10 reflow reference: 320 CSS px wide).
  { id: 'zoom400-1280x1024', viewport: { width: 320, height: 256 }, deviceScaleFactor: 4 }
].filter(v => v.id.includes(FILTER));

const results = [];
const visible = async loc => (await loc.count()) > 0 && (await loc.first().isVisible());

async function probe(page, vp, screen, expect = []) {
  await page.waitForTimeout(400);
  const layout = await layoutProbe(page);
  const reach = await reachProbe(page);
  const missing = [];
  for (const name of expect) if (!(await visible(page.getByRole('button', { name, exact: true })))) missing.push(name);
  const r = { browser: BROWSER, viewport: vp.id, screen, pageOverflowX: layout.pageOverflowX, docScrollWidth: layout.docScrollWidth,
    vw: layout.vw, overflowing: layout.overflowing, brokenWords: layout.brokenWords, clippedControls: layout.clippedControls, clippedText: layout.clippedText,
    controls: reach.controls, unreachable: reach.unreachable, missingActions: missing };
  results.push(r);
  await shot(page, `ux01-${BROWSER}-${vp.id}-${screen}.png`);
  console.log(`${vp.id.padEnd(18)} ${screen.padEnd(22)} overflowX=${r.pageOverflowX} overflow=${r.overflowing.length} clipped=${r.clippedControls.length + r.clippedText.length} broken=${r.brokenWords.length} controls=${r.controls} unreachable=${r.unreachable.length} missing=${missing.join('|') || '-'}`);
  return r;
}

async function goFollowUps(page) {
  const back = page.getByRole('button', { name: 'Volver a Seguimientos' });
  if (await visible(back)) await back.click();
  for (const nav of await page.getByRole('button', { name: 'Seguimientos', exact: true }).all()) if (await nav.isVisible()) { await nav.click(); break; }
  await page.getByRole('heading', { name: 'Seguimientos', level: 1 }).waitFor();
}
async function goConfig(page) {
  const side = page.getByRole('button', { name: 'Configuración', exact: true });
  if (await visible(side)) await side.click();
  else { await page.getByRole('button', { name: 'Más opciones' }).click(); await page.getByRole('button', { name: 'Abrir Configuración' }).click(); }
  await page.getByRole('heading', { name: 'Integración de WhatsApp' }).waitFor();
}
async function openReview(page, customer) {
  await goFollowUps(page);
  await page.getByRole('heading', { name: customer, level: 3 }).click();
  const row = page.getByRole('button', { name: /^Aún no enviado Seguimiento/ }).first();
  await row.waitFor({ timeout: 15000 });
  if ((await row.getAttribute('aria-expanded')) !== 'true') await row.click();
  await page.getByRole('button', { name: 'Abrir en revisión' }).first().click();
  await page.getByRole('heading', { name: 'Lo que recibirá el cliente' }).waitFor({ timeout: 15000 });
}

const browser = await launch(BROWSER);
let storage = null;
for (const vp of VIEWPORTS) {
  const ctx = await browser.newContext({ viewport: vp.viewport, deviceScaleFactor: vp.deviceScaleFactor ?? 1, storageState: storage ?? undefined, locale: 'es-CL' });
  const page = await ctx.newPage();
  if (!storage) { await login(page, 'testuser', process.env.QA_PW); storage = await ctx.storageState(); }
  else { await page.goto('http://localhost:5173'); await page.getByRole('heading', { name: 'Seguimientos', level: 1 }).waitFor({ timeout: 20000 }); }
  try {
    await probe(page, vp, 'queue');
    await openReview(page, 'QA338 Vista Pendiente');
    await probe(page, vp, 'review-pending', ['Aprobar', 'Rechazar', 'Cancelar', 'Corregir']);
    await page.getByRole('button', { name: 'Aprobar', exact: true }).click();
    await page.getByRole('group', { name: 'Confirmar aprobación' }).waitFor();
    await probe(page, vp, 'approve-confirm', ['Confirmar aprobación', 'Cerrar']);
    await page.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await openReview(page, 'QA338 Vista Aprobada');
    await probe(page, vp, 'review-approved', ['Enviar', 'Cancelar']);
    await page.getByRole('button', { name: 'Enviar', exact: true }).click();
    await page.getByRole('group', { name: 'Confirmar envío' }).waitFor();
    await page.getByRole('button', { name: 'Confirmar envío' }).click();
    const alert = page.getByRole('alert').filter({ hasText: 'Los envíos están desactivados en la plataforma.' });
    await alert.waitFor({ timeout: 15000 });
    const box = await alert.boundingBox();
    const r = await probe(page, vp, 'send-refused-error', ['Confirmar envío', 'Cerrar']);
    r.errorBox = box && { x: Math.round(box.x), right: Math.round(box.x + box.width), vw: vp.viewport.width };
    r.errorInside = !!box && box.x >= 0 && box.x + box.width <= vp.viewport.width + 1;
    await page.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await probe(page, vp, 'history');
    await goConfig(page);
    await probe(page, vp, 'integration-templates', ['Editar', 'Verificar propiedad']);
    await page.getByRole('button', { name: 'Editar', exact: true }).click();
    await page.waitForTimeout(800);
    await probe(page, vp, 'integration-edit');
    const cancel = page.getByRole('button', { name: 'Cancelar', exact: true });
    if (await visible(cancel)) await cancel.click();
  } catch (e) {
    results.push({ browser: BROWSER, viewport: vp.id, screen: 'ERROR', error: String(e).split('\n')[0] });
    console.log(`${vp.id} ERROR ${String(e).split('\n')[0]}`);
    await shot(page, `ux01-${BROWSER}-${vp.id}-ERROR.png`);
  }
  await ctx.close();
}
save(`ux01-${BROWSER}${FILTER ? '-' + FILTER : ''}.json`, { browser: BROWSER, version: browser.version(), results });
await browser.close();
