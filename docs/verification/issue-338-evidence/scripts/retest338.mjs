// ABOUTME: Suite #338 retest of the copy part of #418 in the real browser: the AI assistant and workbench footer
// ABOUTME: disclaimers no longer say Dokene sends nothing or that sending is manual only.
//   QA_PW=... node retest338.mjs <chromium|firefox> <customer name>
import { launch, login, save } from './pw338.mjs';

const BROWSER = process.argv[2] ?? 'chromium';
const checks = [];
const check = (name, ok, note = '') => { checks.push({ case: 'UX-04 retest', name, result: ok ? 'PASS' : 'FAIL', note }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${note ? '  (' + note + ')' : ''}`); };
const browser = await launch(BROWSER);
const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'es-CL' })).newPage();
await login(page, 'testuser', process.env.QA_PW);
try {
  await page.getByRole('heading', { name: process.argv[3], level: 3 }).click();
  const detail = page.locator('[role=region][aria-label^="Detalle de "]').first();
  await detail.waitFor();
  const ai = (await page.getByRole('region', { name: 'Asistente IA' }).innerText()).replace(/\s+/g, ' ');
  const all = (await detail.innerText()).replace(/\s+/g, ' ');
  check('the AI disclaimer matches the approval-and-send flow', /La IA no envía nada/.test(ai) && !/Dokene no ha enviado nada|El envío es manual/.test(ai), (ai.match(/Sugerencia generada por IA[^.]*\.[^.]*\./) ?? [''])[0]);
  check('the workbench footer no longer says contacts are manual only', !/Los contactos se realizan manualmente/.test(all) && /WhatsApp un mensaje aprobado/.test(all), (all.match(/Dokene te ayuda[^.]*\.[^.]*\./) ?? [''])[0]);
} catch (e) {
  check('script completed', false, String(e).split('\n')[0]);
}
save(`retest-copy-${BROWSER}.json`, { browser: BROWSER, version: browser.version(), checks });
await browser.close();
