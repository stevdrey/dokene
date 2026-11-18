// ABOUTME: Suite #338 UX-05 invalid credentials in the real browser (tenant B, ADMIN testuser): an unresolvable credential
// ABOUTME: reference and a number the credential does not own (stub "notOwned"), then the original values restored and proven.
//   QA_PW=... node ux05b-338.mjs <chromium|firefox>
import { execFileSync } from 'child_process';
import fs from 'fs';
import { launch, login, save, shot } from './pw338.mjs';

const BROWSER = process.argv[2] ?? 'chromium';
const STUBDIR = `${process.env.QA_HOME ?? process.env.HOME + '/qa333'}/stub`;
const checks = [];
const check = (name, ok, note = '') => { checks.push({ case: 'UX-05 credentials', name, result: ok ? 'PASS' : 'FAIL', note }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${note ? '  (' + note + ')' : ''}`); };
const stubCount = () => fs.readFileSync(`${STUBDIR}/requests.log`, 'utf8').split('\n').filter(Boolean).length;

const browser = await launch(BROWSER);
const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'es-CL' })).newPage();
await login(page, 'testuser', process.env.QA_PW);
const region = () => page.getByRole('region', { name: 'Integración de WhatsApp' });
const panelText = async () => (await region().innerText()).replace(/\s+/g, ' ');
async function edit(ref) {
  await page.getByRole('button', { name: 'Editar', exact: true }).click();
  const f = page.getByLabel('Referencia de credencial');
  // Typed key by key, as a person would: fill() over WebDriver BiDi does not reach React's state in Firefox.
  await f.click(); await page.keyboard.press('ControlOrMeta+a'); await page.keyboard.press('Backspace'); await page.keyboard.type(ref);
  if ((await f.inputValue()) !== ref) throw new Error('reference field did not take the typed value');
  await page.getByRole('button', { name: 'Guardar', exact: true }).click();
  await page.getByText('Integración guardada.').waitFor({ timeout: 15000 });
}
async function verify() {
  await page.getByRole('button', { name: 'Verificar propiedad', exact: true }).click();
  await page.waitForTimeout(3000);
  return (await page.getByRole('alert').allInnerTexts()).concat(await page.getByRole('status').allInnerTexts()).filter(Boolean).join(' | ').replace(/\s+/g, ' ');
}
const BAD_REF = 'qa338-no-existe';
// dirty: the bad reference is saved (cleared once the original is saved again). unproven: from the first save until
// ownership is proven again at the end; a save can clear the proof. The finally block repairs both.
let original = null;
let dirty = false;
let unproven = false;
// UX05B_FAIL_AFTER=bad-ref|not-owned throws at that point, to exercise the cleanup (reproduction aid only).
const failAfter = point => { if (process.env.UX05B_FAIL_AFTER === point) throw new Error(`injected failure after ${point}`); };
const switchToB = async () => {
  await page.getByRole('button', { name: 'Seleccionar espacio de trabajo' }).first().click();
  await page.getByRole('option', { name: /QA Café Sur/ }).or(page.getByRole('menuitem', { name: /QA Café Sur/ })).or(page.getByRole('button', { name: /QA Café Sur/ })).first().click();
  await page.getByRole('heading', { name: 'Seguimientos', level: 1 }).waitFor();
};
const inB = async () => /QA Café Sur/.test(await page.getByRole('button', { name: 'Seleccionar espacio de trabajo' }).first().innerText());
try {
  await switchToB();
  await page.getByRole('button', { name: 'Configuración', exact: true }).click();
  await region().waitFor();
  original = (await region().locator('code').first().innerText()).trim();
  // A previous run that died mid-case may have left the bad value: fall back to the stack's known tenant B reference.
  if (original === BAD_REF) original = process.env.B_SECRET_REF ?? 'qa333-token-b';
  check('tenant B integration opened (ADMIN); original reference read from the panel', original.length > 0, `reference ${original}`);

  // 1. A reference that resolves to no credential.
  unproven = true;
  await edit(BAD_REF);
  dirty = true;
  let s0 = stubCount();
  const t1 = await verify();
  check('unresolvable reference: refused, no provider request', t1.length > 0 && !/comprobada/i.test(t1) && stubCount() === s0, `ui "${t1}"`);
  // The operator must be pointed at the configuration, not told to wait for a provider outage.
  check('unresolvable reference: the copy points at the credential reference, not at a provider outage', /referencia|credencial/i.test(t1) && !/Intenta más tarde/.test(t1), `ui "${t1}"`);
  await shot(page, `ux05b-${BROWSER}-badref.png`);
  failAfter('bad-ref');

  // 2. Restore the reference; the provider says the number is not owned by this credential.
  await edit(original);
  dirty = false;
  fs.writeFileSync(`${STUBDIR}/phones`, 'notOwned');
  s0 = stubCount();
  const t2 = await verify();
  failAfter('not-owned');
  check('number not owned by the credential: refused with the "No se pudo comprobar la propiedad…" guidance', /No se pudo comprobar la propiedad/.test(t2), `ui "${t2}", provider requests ${stubCount() - s0}`);

  // 3. Restore the stub and prove again.
  fs.writeFileSync(`${STUBDIR}/phones`, 'owned');
  const t3 = await verify();
  const panel = await panelText();
  check('restored: ownership proven again, the panel shows "Propiedad comprobada"', /Propiedad del número comprobada/.test(t3) || /Propiedad comprobada/.test(panel), `ui "${t3}"`);
  if (/Propiedad del número comprobada/.test(t3) || /Propiedad comprobada/.test(panel)) unproven = false;
  await shot(page, `ux05b-${BROWSER}-restored.png`);
} catch (e) {
  check('script completed', false, String(e).split('\n')[0]);
  await shot(page, `ux05b-${BROWSER}-ERROR.png`).catch(() => {});
} finally {
  fs.writeFileSync(`${STUBDIR}/phones`, 'owned');
  // A failure after the first save must not leave tenant B on the bad reference or without a proof: put the original
  // back (only in tenant B, checked after the reload) and prove ownership again.
  if ((dirty || unproven) && original) {
    try {
      await page.reload();
      await page.getByRole('heading', { name: 'Seguimientos', level: 1 }).or(page.getByRole('heading', { name: 'Integración de WhatsApp' })).first().waitFor();
      if (!(await inB())) await switchToB();
      if (!(await inB())) throw new Error('could not return to tenant B; nothing was changed');
      await page.getByRole('button', { name: 'Configuración', exact: true }).click();
      await region().waitFor();
      if (dirty) { await edit(original); dirty = false; }
      const t = await verify();
      const ok = /Propiedad del número comprobada/.test(t) || /Propiedad comprobada/.test(await panelText());
      check('cleanup: tenant B reference restored and proven after a failure', ok && (await region().locator('code').first().innerText()).trim() === original, `reference ${original}; ui "${t}"`);
    } catch (e) {
      check('cleanup: tenant B reference restored and proven after a failure', false, `restore it by hand to ${original} and verify ownership: ${String(e).split('\n')[0]}`);
    }
  }
}
save(`ux05b-${BROWSER}.json`, { browser: BROWSER, version: browser.version(), checks });
await browser.close();
