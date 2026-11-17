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
try {
  await page.getByRole('button', { name: 'Seleccionar espacio de trabajo' }).first().click();
  await page.getByRole('option', { name: /QA Café Sur/ }).or(page.getByRole('menuitem', { name: /QA Café Sur/ })).or(page.getByRole('button', { name: /QA Café Sur/ })).first().click();
  await page.getByRole('heading', { name: 'Seguimientos', level: 1 }).waitFor();
  await page.getByRole('button', { name: 'Configuración', exact: true }).click();
  await region().waitFor();
  const original = (await region().locator('code').first().innerText()).trim();
  check('tenant B integration opened (ADMIN); original reference read from the panel', original.length > 0, `reference ${original}`);

  // 1. A reference that resolves to no credential.
  await edit('qa338-no-existe');
  let s0 = stubCount();
  const t1 = await verify();
  check('unresolvable reference: refused, no provider request', t1.length > 0 && !/comprobada/i.test(t1) && stubCount() === s0, `ui "${t1}"`);
  // The operator must be pointed at the configuration, not told to wait for a provider outage.
  check('unresolvable reference: the copy points at the credential reference, not at a provider outage', /referencia|credencial/i.test(t1) && !/Intenta más tarde/.test(t1), `ui "${t1}"`);
  await shot(page, `ux05b-${BROWSER}-badref.png`);

  // 2. Restore the reference; the provider says the number is not owned by this credential.
  await edit(original);
  fs.writeFileSync(`${STUBDIR}/phones`, 'notOwned');
  s0 = stubCount();
  const t2 = await verify();
  check('number not owned by the credential: refused with the "No se pudo comprobar la propiedad…" guidance', /No se pudo comprobar la propiedad/.test(t2), `ui "${t2}", provider requests ${stubCount() - s0}`);

  // 3. Restore the stub and prove again.
  fs.writeFileSync(`${STUBDIR}/phones`, 'owned');
  const t3 = await verify();
  const panel = await panelText();
  check('restored: ownership proven again, the panel shows "Propiedad comprobada"', /Propiedad del número comprobada/.test(t3) || /Propiedad comprobada/.test(panel), `ui "${t3}"`);
  await shot(page, `ux05b-${BROWSER}-restored.png`);
} catch (e) {
  fs.writeFileSync(`${STUBDIR}/phones`, 'owned');
  check('script completed', false, String(e).split('\n')[0]);
  await shot(page, `ux05b-${BROWSER}-ERROR.png`);
}
save(`ux05b-${BROWSER}.json`, { browser: BROWSER, version: browser.version(), checks });
await browser.close();
