// ABOUTME: Suite #338 UX-06 text fidelity in the real browser: the AI draft for a customer whose name holds accents, emoji
// ABOUTME: and HTML-like text, then an operator-typed multiline/long/HTML-like body through copy, preview, submit and review.
//   QA_PW=... node ux06-338.mjs <chromium|firefox> <customer name>
import { launch, login, save, shot, layoutProbe } from './pw338.mjs';

const BROWSER = process.argv[2] ?? 'chromium';
const CUSTOMER = process.argv[3];
const checks = [];
const check = (name, ok, note = '') => { checks.push({ case: 'UX-06', name, result: ok ? 'PASS' : 'FAIL', note }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${note ? '  (' + note + ')' : ''}`); };
// The operator's body: accents, ñ, emoji (incl. a ZWJ sequence), typographic quotes, multiline, HTML-like text, long.
const LONG = 'Este texto es largo a propósito para comprobar el ajuste de línea sin desbordes en pantallas angostas. ';
const BODY = ['Hola Ñandú José-María 🎉👩‍💻,', 'Tu pedido “Café de Altura” está listo — ¿lo retiras mañana? ¡Gracias!',
  '<b>no es negrita</b> <script>window.__qa338=1</script> <img src=x onerror="window.__qa338=2"> &amp; {{7*7}}', LONG.repeat(5).trim()].join(process.env.BODY_MODE === 'single' ? ' ' : '\n');
const MULTI = BODY.includes('\n');

const browser = await launch(BROWSER);
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'es-CL' });
if (BROWSER === 'chromium') await ctx.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: 'http://localhost:5173' });
const page = await ctx.newPage();
let dialogs = 0; page.on('dialog', d => { dialogs++; d.dismiss(); });
const injected = () => page.evaluate(() => ({ flag: window.__qa338 ?? null,
  b: [...document.querySelectorAll('main b, main strong')].filter(e => /negrita/i.test(e.textContent)).length,
  img: document.querySelectorAll('main img[src="x"]').length, script: [...document.querySelectorAll('main script')].length }));
const clean = i => i.flag === null && i.b === 0 && i.img === 0 && i.script === 0;
await login(page, 'testuser', process.env.QA_PW);
try {
  await page.getByRole('heading', { name: CUSTOMER, level: 3 }).click();
  const name = (await page.getByRole('region', { name: `Detalle de ${CUSTOMER}` }).getByRole('heading', { level: 2 }).first().innerText()).trim();
  check('the customer name renders as literal text (accents, emoji, <b>, quotes)', name === CUSTOMER, name);
  await page.getByRole('button', { name: 'Obtener recomendación' }).click();
  await page.getByRole('button', { name: 'Generar borrador' }).click({ timeout: 30000 });
  const area = page.getByRole('region', { name: 'Asistente IA' }).locator('textarea').first();
  await area.waitFor({ timeout: 30000 });
  const draft = await area.inputValue();
  check('the AI draft keeps the name verbatim in an editable text box, nothing rendered as markup', draft.includes(CUSTOMER) && clean(await injected()), JSON.stringify(draft.slice(0, 160)));

  // Replace the draft with the operator's body, typed (fill() over BiDi does not reach React in Firefox).
  await area.click(); await page.keyboard.press('ControlOrMeta+a'); await page.keyboard.press('Backspace');
  for (const [i, line] of BODY.split('\n').entries()) { if (i) await page.keyboard.press('Shift+Enter'); if (BROWSER === 'firefox') await page.keyboard.type(line); else await page.keyboard.insertText(line); }
  const typed = await area.inputValue();
  check('the edited body is held exactly (code points, newlines)', typed === BODY, `${[...typed].length} code points, ${typed.split('\n').length} lines`);
  const counter = (await page.getByRole('region', { name: 'Asistente IA' }).innerText()).match(/\d+\s*\/\s*1000/);
  check('the length counter counts code points (emoji count as their code points, max 1000)', counter && Number(counter[0].split('/')[0]) === [...BODY].length, counter?.[0] ?? 'no counter');

  if (BROWSER === 'chromium') {
    await page.getByRole('button', { name: 'Copiar borrador' }).click();
    await page.waitForTimeout(500);
    const clip = await page.evaluate(() => navigator.clipboard.readText());
    check('"Copiar borrador" puts exactly the shown text on the clipboard', clip === BODY, clip === BODY ? 'identical' : JSON.stringify(clip.slice(0, 120)));
  }
  await page.getByRole('button', { name: 'Revisar y enviar' }).click();
  await page.getByRole('button', { name: 'Enviar a aprobación' }).waitFor();
  // The compose section is labelled by the customer name and shows the final text before approval.
  const composeSection = page.locator('section.message-review').first();
  const preview = [(await composeSection.innerText()), ...(await composeSection.locator('textarea').evaluateAll(t => t.map(x => x.value)))].join('\n').replace(/\r/g, '');
  check('the compose preview shows the body as text with its lines (HTML-like content literal, no markup executed)', BODY.split('\n').every(l => preview.includes(l)) && clean(await injected()) && dialogs === 0, JSON.stringify(preview.slice(0, 200)));
  // Only the compose's own copy (not the body, whose filler text says "ajuste de línea") can carry the warning.
  const composeCopy = (await composeSection.innerText()).replace(BODY, '');
  if (MULTI) check('before submitting, the compose warns that line breaks cannot be sent', /(saltos? de línea|varias líneas|una sola línea)/i.test(composeCopy), /(saltos? de línea|varias líneas|una sola línea)/i.test(composeCopy) ? 'warning shown' : 'no warning in the compose');
  // Retest of #417: a body the server would refuse is stopped in the compose, before any request.
  const submitBtn = page.getByRole('button', { name: 'Enviar a aprobación' });
  if (MULTI && await submitBtn.isDisabled()) {
    const warning = (await composeSection.innerText()).match(/WhatsApp no admite[^.]*\./)?.[0] ?? '';
    check('the unsendable body is stopped before submit: submit disabled with the reason shown', warning !== '' && (await composeSection.locator('textarea').first().getAttribute('aria-invalid')) === 'true', warning);
    check('no markup from customer or body text was executed or inserted anywhere', clean(await injected()) && dialogs === 0, JSON.stringify(await injected()));
    save(`ux06-${BROWSER}-multiline.json`, { browser: BROWSER, version: browser.version(), body: BODY, checks });
    await browser.close();
    process.exit(0);
  }
  await submitBtn.click();
  await page.getByRole('button', { name: 'Confirmar envío a aprobación' }).click();
  await page.waitForTimeout(3000);
  const outcome = `${(await page.getByRole('status').allInnerTexts()).join(' | ')} ${(await page.getByRole('alert').allInnerTexts()).join(' | ')}`.replace(/\s+/g, ' ');
  const submitted = /Mensaje enviado a aprobación/.test(outcome);
  // A sendable single-line body must reach approval; only the multiline negative case may end in a readable refusal.
  if (MULTI) check('submitting the multiline body gives a clear outcome (submitted, or a readable refusal)', submitted || /(Revisa|no pasó la validación|no se pudo)/i.test(outcome), outcome.trim().slice(0, 220));
  else check('the single-line body with accents, emoji and HTML-like text is submitted to approval', submitted, outcome.trim().slice(0, 220));
  if (MULTI && !submitted) check('the refusal of a multiline body tells the operator what to change (line breaks)', /(salto|línea|tabul|espacios)/i.test(outcome), outcome.trim().slice(0, 160));
  if (submitted) {
    const review = (await page.locator('section.message-review').first().innerText()).replace(/\r/g, '');
    check('the review renders the stored body with the same lines, accents and emoji', BODY.split('\n').every(l => review.includes(l)), `${review.split('\n').length} lines rendered`);
  }
  check('no markup from customer or body text was executed or inserted anywhere', clean(await injected()) && dialogs === 0, JSON.stringify(await injected()));
  for (const w of [390, 320]) {
    await page.setViewportSize({ width: w, height: 800 });
    await page.waitForTimeout(500);
    const l = await layoutProbe(page);
    check(`long text at ${w} px: no page overflow, nothing clipped`, !l.pageOverflowX && l.overflowing.length === 0 && l.clippedText.length === 0, `scrollWidth ${l.docScrollWidth}/${l.vw}; ${l.overflowing.concat(l.clippedText).join('; ')}`);
    await shot(page, `ux06-${BROWSER}-${w}.png`);
  }
} catch (e) {
  check('script completed', false, String(e).split('\n')[0]);
  await shot(page, `ux06-${BROWSER}-ERROR.png`);
}
save(`ux06-${BROWSER}-${MULTI ? 'multiline' : 'single'}.json`, { browser: BROWSER, version: browser.version(), body: BODY, checks });
await browser.close();
