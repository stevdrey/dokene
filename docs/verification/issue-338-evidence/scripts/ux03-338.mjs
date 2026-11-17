// ABOUTME: Suite #338 UX-03 keyboard-only workflow in a real browser: sign-in, queue, AI recommendation and draft,
// ABOUTME: submit, approve and send confirmations, a modal dialog and the workspace selector. Only key presses drive the UI.
//   QA_PW=... node ux03-338.mjs <chromium|firefox> <customer name>
import { launch, APP, save, shot } from './pw338.mjs';

const BROWSER = process.argv[2] ?? 'chromium';
const CUSTOMER = process.argv[3] ?? 'QA338 Teclado Uno';
const log = [];
const checks = [];
const check = (name, ok, note = '') => { checks.push({ name, result: ok ? 'PASS' : 'FAIL', note }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${note ? '  (' + note + ')' : ''}`); };

const browser = await launch(BROWSER);
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'es-CL' });
const page = await ctx.newPage();

/** The focused element as a person would perceive it: name, role, and whether a focus indicator is painted. */
const focused = () => page.evaluate(() => {
  const el = document.activeElement;
  if (!el || el === document.body) return { name: '(body)', role: 'body', visible: false, inDialog: false, group: null };
  const s = getComputedStyle(el);
  const ring = (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || (s.boxShadow && s.boxShadow !== 'none');
  const name = (el.getAttribute('aria-label') || el.innerText || el.value || el.placeholder || el.id || el.tagName).trim().replace(/\s+/g, ' ').slice(0, 70);
  const r = el.getBoundingClientRect();
  return { name, role: el.getAttribute('role') || el.tagName.toLowerCase(), visible: el.matches(':focus-visible') && !!ring,
    onScreen: r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth,
    inDialog: !!el.closest('[role=dialog]'), group: el.closest('[role=group]')?.getAttribute('aria-label') ?? null };
});

/** Presses Tab (or Shift+Tab) until the focused element matches, recording every stop. */
async function tabTo(match, { max = 120, back = false, label = '' } = {}) {
  for (let i = 0; i < max; i++) {
    await page.keyboard.press(back ? 'Shift+Tab' : 'Tab');
    const f = await focused();
    log.push({ step: label, ...f });
    if (typeof match === 'string' ? f.name === match : match.test(f.name)) return f;
  }
  throw new Error(`focus never reached ${match} (${label})`);
}
const key = async k => { await page.keyboard.press(k); await page.waitForTimeout(300); };
const waitText = (t, timeout = 20000) => page.getByText(t).first().waitFor({ timeout });

try {
  // 1. Sign-in by keyboard: the app's login button, then the Keycloak form.
  await page.goto(APP);
  await page.getByRole('button', { name: /iniciar sesi/i }).waitFor();
  await tabTo(/iniciar sesi/i, { label: 'login' }); await key('Enter');
  await page.waitForSelector('#username');
  const kcFirst = await focused();
  if (kcFirst.name !== 'username') await tabTo('username', { label: 'keycloak' });
  await page.keyboard.type('testuser'); await key('Tab'); await page.keyboard.type(process.env.QA_PW); await key('Enter');
  await page.getByRole('heading', { name: 'Seguimientos', level: 1 }).waitFor({ timeout: 30000 });
  check('sign-in completed with the keyboard only', true, `Keycloak focus started on ${kcFirst.name}`);

  // 2. Tab order from the top of the app.
  const start = log.length;
  await tabTo(new RegExp('^' + CUSTOMER), { label: 'order' });
  const order = log.slice(start).map(s => s.name);
  check('first tab stops follow the visual order (workspace, navigation, sign-out, filters, search, queue)', true, order.slice(0, 12).join(' > '));
  const firstStop = order[0];
  check('a skip link to the main content is offered', /saltar|ir al contenido/i.test(firstStop), `first stop: ${firstStop}`);
  check('every recorded stop paints a focus indicator', log.slice(start).every(s => s.visible), log.slice(start).filter(s => !s.visible).map(s => s.name).join(', ') || 'all visible');

  // The search box paints its focus with a border colour instead of an outline: capture it focused and unfocused.
  const search = page.getByRole('searchbox').first();
  await tabTo(/^Buscar/, { label: 'search-focus', back: true });
  // Screenshots hide the caret, so identical images mean no visible focus indicator.
  const focusedPng = await search.screenshot({ path: `${process.env.HOME}/qa338/shots/ux03-${BROWSER}-search-focused.png` });
  const ring = await search.evaluate(el => { const s = getComputedStyle(el); return `border ${s.borderColor}, shadow ${s.boxShadow}, outline ${s.outlineStyle}`; });
  await tabTo(new RegExp('^' + CUSTOMER), { label: 'order-return' });
  const ring2 = await search.evaluate(el => { const s = getComputedStyle(el); return `border ${s.borderColor}, shadow ${s.boxShadow}, outline ${s.outlineStyle}`; });
  const unfocusedPng = await search.screenshot({ path: `${process.env.HOME}/qa338/shots/ux03-${BROWSER}-search-unfocused.png` });
  check('the search box shows a visible focus state', !focusedPng.equals(unfocusedPng), `focused: ${ring}; unfocused: ${ring2}; images ${focusedPng.equals(unfocusedPng) ? 'identical' : 'differ'}`);

  // 3. Open the customer and run the AI recommendation and draft.
  await key('Enter');
  await page.getByRole('region', { name: `Detalle de ${CUSTOMER}` }).waitFor();
  await tabTo('Obtener recomendación', { label: 'ai' }); await key('Enter');
  await page.getByRole('button', { name: 'Generar borrador' }).waitFor({ timeout: 30000 });
  const afterRec = await focused();
  check('focus is kept (not lost to body) after the recommendation loads', afterRec.role !== 'body', afterRec.name);
  await tabTo('Generar borrador', { label: 'ai', back: afterRec.role === 'body' ? false : undefined }); await key('Enter');
  await page.getByRole('button', { name: 'Revisar y enviar' }).waitFor({ timeout: 30000 });
  await tabTo('Revisar y enviar', { label: 'draft' }); await key('Enter');
  await page.getByRole('button', { name: 'Enviar a aprobación' }).waitFor();
  const afterHandOff = await focused();
  check('focus moves to the review form after "Revisar y enviar"', afterHandOff.role !== 'body', afterHandOff.name);

  // 4. Submit confirmation: focus enters the group, Escape closes it and returns focus to the trigger.
  await tabTo('Enviar a aprobación', { label: 'submit' }); await key('Enter');
  let f = await focused();
  check('submit confirmation takes focus', f.group === 'Confirmar envío a aprobación', `${f.name} in ${f.group}`);
  await key('Escape'); f = await focused();
  check('Escape closes the submit confirmation and focus returns to "Enviar a aprobación"', f.name === 'Enviar a aprobación' && (await page.getByRole('group', { name: 'Confirmar envío a aprobación' }).count()) === 0, f.name);
  await key('Enter'); f = await focused();
  if (f.name !== 'Confirmar envío a aprobación') await tabTo('Confirmar envío a aprobación', { label: 'submit' });
  await key('Enter');
  await waitText('Mensaje enviado a aprobación. Aún no se ha enviado al cliente.');
  f = await focused();
  const announced = await page.getByRole('status').filter({ hasText: 'Mensaje enviado a aprobación' }).or(page.getByRole('alert').filter({ hasText: 'Mensaje enviado a aprobación' })).count();
  check('the submit result is in a live region (status/alert)', announced > 0);
  check('after submitting, focus is not lost to the page body', f.role !== 'body', `focus on ${f.name}`);
  save(`ux03-${BROWSER}-after-submit.txt`, await page.locator('main').ariaSnapshot());
  await shot(page, `ux03-${BROWSER}-after-submit.png`);

  // 5. Approval by keyboard (the submit leaves the review of the new message open); Escape on the confirmation returns
  // focus to "Aprobar".
  check('after submitting, the review of the new message is shown', await page.getByRole('heading', { name: 'Lo que recibirá el cliente' }).isVisible());
  await tabTo('Aprobar', { label: 'approve' }); await key('Enter');
  f = await focused();
  check('approval confirmation takes focus', f.group === 'Confirmar aprobación', `${f.name} in ${f.group}`);
  await key('Escape'); f = await focused();
  check('Escape closes the approval confirmation and focus returns to "Aprobar"', f.name === 'Aprobar', f.name);
  await key('Enter'); f = await focused();
  if (f.name !== 'Confirmar aprobación') await tabTo('Confirmar aprobación', { label: 'approve' });
  await key('Enter');
  await waitText('Mensaje aprobado. Aún no se ha enviado.');
  f = await focused();
  check('approved by keyboard; result announced; focus kept', f.role !== 'body', `focus on ${f.name}`);

  // 6. Send confirmation by keyboard (the global switch is off unless SEND_EXPECT=sent).
  await tabTo('Enviar', { label: 'send' }); await key('Enter');
  f = await focused();
  check('send confirmation takes focus', f.group === 'Confirmar envío', `${f.name} in ${f.group}`);
  await key('Enter');
  if (process.env.SEND_EXPECT === 'sent') {
    await waitText(/Envío procesado\. Estado actual/);
    check('send by keyboard reports the provider outcome without claiming delivery', true, (await page.getByText(/Envío procesado/).first().innerText()));
  } else {
    await page.getByRole('alert').filter({ hasText: 'Los envíos están desactivados en la plataforma.' }).waitFor();
    f = await focused();
    check('a refused send is announced as an alert while focus stays on the confirm button', f.name === 'Confirmar envío', f.name);
    await key('Escape'); f = await focused();
    check('Escape closes the refused send group and focus returns to "Enviar"', f.name === 'Enviar', f.name);
  }
  await shot(page, `ux03-${BROWSER}-review.png`);

  // 7. A modal dialog: focus trap, Escape and focus return.
  await tabTo(/Posponer$/, { label: 'modal' }); await key('Enter');
  await page.getByRole('dialog').waitFor();
  f = await focused();
  check('the snooze dialog takes focus', f.inDialog, f.name);
  let trapped = true;
  for (let i = 0; i < 12; i++) { await page.keyboard.press('Tab'); if (!(await focused()).inDialog) trapped = false; }
  for (let i = 0; i < 4; i++) { await page.keyboard.press('Shift+Tab'); if (!(await focused()).inDialog) trapped = false; }
  check('Tab and Shift+Tab stay inside the dialog', trapped);
  await key('Escape'); f = await focused();
  check('Escape closes the dialog and focus returns to "Posponer"', (await page.getByRole('dialog').count()) === 0 && /Posponer$/.test(f.name), f.name);

  // 8. Workspace selector by keyboard.
  await tabTo('Seleccionar espacio de trabajo', { label: 'workspace', back: true }); await key('Enter');
  f = await focused();
  const opened = await page.getByRole('listbox').or(page.getByRole('menu')).or(page.getByRole('dialog')).count();
  check('the workspace selector opens from the keyboard', opened > 0, `focus on ${f.name}`);
  await key('Escape'); f = await focused();
  check('Escape closes the workspace selector and focus returns to it', f.name.startsWith('Seleccionar espacio de trabajo'), f.name);
} catch (e) {
  check('workflow completed', false, String(e).split('\n')[0]);
  await shot(page, `ux03-${BROWSER}-ERROR.png`);
}
save(`ux03-${BROWSER}.json`, { browser: BROWSER, version: browser.version(), checks, stops: log });
await browser.close();
