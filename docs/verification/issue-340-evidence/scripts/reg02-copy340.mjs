// ABOUTME: Suite #340 REG-02 copy leg: in real Chromium with clipboard permission, signs in, asks the live AI for a
// ABOUTME: recommendation and a draft for the REG-02 customer, edits the draft, presses "Copiar borrador", reads the clipboard.
// Usage: PW_HOME=<dir with playwright-core> QA_PW=<testuser password> node reg02-copy340.mjs   (2 live AI calls)
import { launch, login, APP } from '../../issue-338-evidence/scripts/pw338.mjs';

const b = await launch('chromium');
const ctx = await b.newContext({ viewport: { width: 1280, height: 900 } });
await ctx.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: APP });
const p = await ctx.newPage();
const out = (k, v) => console.log(`${k}\t${v}`);
try {
  await login(p, 'testuser', process.env.QA_PW);
  await p.fill('#followup-search-input', 'Lucía Borrador');
  await p.getByRole('region', { name: /Detalle de QA340 Lucía Borrador/ }).waitFor({ timeout: 10000 });
  await p.getByRole('button', { name: 'Obtener recomendación' }).click();
  await p.getByRole('button', { name: 'Generar borrador' }).waitFor({ timeout: 60000 });
  await p.getByRole('button', { name: 'Generar borrador' }).click();
  const ta = p.getByRole('textbox', { name: 'Borrador del mensaje (editable)' });
  await ta.waitFor({ timeout: 60000 });
  const generated = await ta.inputValue();
  out('generated_length', generated.length);
  out('generated_is_spanish', /\b(Hola|compra|café|filtros|necesitas|reponer)/i.test(generated));
  out('generated_no_link_or_price', !/(https?:|www\.|\$|\d+\s?%|descuento)/i.test(generated));
  await ta.click(); await p.keyboard.press('Control+End'); await p.keyboard.type(' (editado QA340 Chromium)');
  const edited = await ta.inputValue();
  await p.getByRole('button', { name: 'Copiar borrador' }).click();
  await p.waitForTimeout(800);
  const clip = await p.evaluate(() => navigator.clipboard.readText());
  out('clipboard_equals_edited_draft', clip === edited);
  out('edited_suffix_in_clipboard', clip.endsWith('(editado QA340 Chromium)'));
  const body = await p.innerText('main');
  out('copy_feedback', (body.match(/(Borrador copiado[^\n]*|Copiado[^\n]*|No se pudo copiar[^\n]*)/) || ['none'])[0]);
  out('local_edit_notice', body.includes('Edición local: no se guarda ni se envía.'));
  out('no_send_or_approve_without_review', !(await p.getByRole('button', { name: /^Aprobar|^Enviar$/ }).count()));
} catch (e) { out('ERROR', String(e).slice(0, 300)); }
await b.close();
