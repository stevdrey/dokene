import { launchChrome, Browser, login, sleep, FOCUS } from './cdp.mjs';
import { writeFileSync } from 'node:fs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
const log = []; const L = (s) => { console.log(s); log.push(s); };
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await sleep(500);
const fmt = (f) => `${f.tag}${f.role ? '[' + f.role + ']' : ''} "${f.name}" fv=${f.focusVisible} outline=${f.outline}${f.shadow ? ' +shadow' : ''}${f.inDialog ? ' (in dialog)' : ''}${f.inViewport ? '' : ' (off-screen)'}`;
async function tab(n, shift = false, tag = '') { for (let i = 0; i < n; i++) { await p.key('Tab', { shift }); const f = await p.ev(FOCUS); L(`  ${shift ? 'Shift+' : ''}Tab ${tag}${i + 1}: ${fmt(f)}`); } }
await p.ev('document.body.focus()'); await p.send('Page.bringToFront');
L('== UX-03 keyboard, Chrome, 1440x900, user testoperator');
L('-- A. from page start: tab order of the shell and first cards (12 stops)'); await tab(12);
// search to narrow to one customer using the keyboard only
L('-- B. focus search box by tabbing, filter with the keyboard');
await p.ev(`document.querySelector('input[placeholder*="Buscar"]').focus()`); await p.type('Dormant'); await sleep(1200);
L('-- C. Tab from search to the first card, Enter selects it'); await tab(3);
const f1 = await p.ev(FOCUS); L('   focused: ' + fmt(f1));
await p.key('Enter'); await sleep(1000); L('   after Enter on card -> ' + fmt(await p.ev(FOCUS)) + ' | detail shown: ' + await p.ev('document.body.innerText.includes("Asistente IA")'));
L('-- D. Tab to "Obtener recomendación" (up to 25 stops), Enter');
let found = false; for (let i = 0; i < 25 && !found; i++) { await p.key('Tab'); const f = await p.ev(FOCUS); if (f.name.includes('Obtener recomendación')) { found = true; L(`   reached after ${i + 1} Tabs: ${fmt(f)}`); } }
await p.key('Enter'); L('   Enter pressed; waiting for result'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")||document.body.innerText.includes("no está disponible")', 45000); await sleep(700);
L('   focus after the live result arrives: ' + fmt(await p.ev(FOCUS)));
L('   live region(s) present: ' + JSON.stringify(await p.ev(`[...document.querySelectorAll('[aria-live],[role=status],[role=alert]')].map(e=>(e.getAttribute('role')||'')+'/'+(e.getAttribute('aria-live')||'')+' "'+e.textContent.trim().slice(0,50)+'"')`)));
L('-- E. Tab to "Generar borrador", Enter'); found = false; for (let i = 0; i < 12 && !found; i++) { await p.key('Tab'); const f = await p.ev(FOCUS); if (f.name.includes('Generar borrador')) { found = true; L(`   reached after ${i + 1} Tabs: ${fmt(f)}`); } }
await p.key('Enter'); await p.waitFor('!!document.querySelector("textarea")', 45000); await sleep(700);
const fe = await p.ev(FOCUS); L('   focus after the draft arrives: ' + fmt(fe) + (fe.tag === 'textarea' ? '  (focus moved to the editor)' : '  (focus NOT moved to the editor)'));
L('-- F. edit with the keyboard (type 12 chars at the caret), then Tab to Copiar / Regenerar');
await p.ev(`document.querySelector('textarea').focus()`); await p.type(' [edit QA] '); L('   counter text now: ' + await p.ev(`(document.body.innerText.match(/\\d+ \\/ 1000/)||[''])[0]`));
await tab(1, false, 'after-editor '); await tab(1, false, 'after-editor '); await tab(1, false, 'after-editor ');
L('-- G. Shift+Tab back to the editor'); await tab(3, true, 'back ');
L('-- H. Regenerar with the keyboard: expect either regenerate directly or a discard confirmation when edited');
await p.ev(`[...document.querySelectorAll('button')].find(b=>b.textContent.includes('Regenerar borrador')).focus()`); await p.key('Enter'); await sleep(1200);
L('   after Enter on Regenerar -> ' + fmt(await p.ev(FOCUS)) + ' | dialog open: ' + await p.ev('!!document.querySelector("[role=dialog],[aria-modal=true]")') + ' | body has confirm text: ' + await p.ev('/descart|perder|ediciones/i.test(document.body.innerText)'));
if (await p.ev('!!document.querySelector("[role=dialog],[aria-modal=true]")')) { L('   dialog tab cycle (6 stops, should stay inside):'); await tab(6); await p.key('Escape'); await sleep(500); L('   Escape -> dialog closed: ' + !(await p.ev('!!document.querySelector("[role=dialog],[aria-modal=true]")')) + ' | focus returns to: ' + fmt(await p.ev(FOCUS))); }
L('-- I. modal dialogs from the keyboard: Posponer, Descartar, Registrar seguimiento (open with Enter, Tab cycle, Escape, focus return)');
for (const label of ['Posponer', 'Descartar', 'Registrar seguimiento']) {
  const ok = await p.ev(`(()=>{const b=[...document.querySelectorAll('button')].filter(x=>x.offsetParent!==null).find(x=>x.textContent.replace(/\\s+/g,' ').includes(${JSON.stringify(label)}));if(!b)return false;b.focus();return true})()`); if (!ok) { L(`   ${label}: button not found`); continue; }
  await p.key('Enter'); await sleep(700); const open = await p.ev('!!document.querySelector("[role=dialog],[aria-modal=true]")'); L(`   ${label}: dialog open=${open}; initial focus: ${fmt(await p.ev(FOCUS))}`);
  if (open) { const trail = []; for (let i = 0; i < 9; i++) { await p.key('Tab'); const f = await p.ev(FOCUS); trail.push(`${f.tag}:"${f.name.slice(0, 16)}"${f.inDialog ? '' : '!OUT'}`); } L('     9 Tabs: ' + trail.join(' > ')); const sh = []; for (let i = 0; i < 2; i++) { await p.key('Tab', { shift: true }); const f = await p.ev(FOCUS); sh.push(`${f.tag}:"${f.name.slice(0, 16)}"${f.inDialog ? '' : '!OUT'}`); } L('     2 Shift+Tabs: ' + sh.join(' > '));
    await p.key('Escape'); await sleep(600); const closed = !(await p.ev('!!document.querySelector("[role=dialog],[aria-modal=true]")')); L(`     Escape closes: ${closed}; focus returns to: ${fmt(await p.ev(FOCUS))}`); }
}
L('-- J. logout with the keyboard'); const lo = await p.ev(`(()=>{const b=[...document.querySelectorAll('button')].filter(x=>x.offsetParent!==null).find(x=>x.textContent.includes('Cerrar sesión'));if(!b)return false;b.focus();return true})()`); L('   found logout button: ' + lo);
await p.key('Enter'); await sleep(2500); L('   after Enter: url=' + await p.ev('location.href.slice(0,60)') + ' | text: ' + (await p.ev('document.body.innerText.slice(0,80)')).replace(/\s+/g, ' '));
writeFileSync('/home/stevdrey/qa122/out/ux03-keyboard-chrome.txt', log.join('\n')); c.proc.kill(); process.exit(0);
