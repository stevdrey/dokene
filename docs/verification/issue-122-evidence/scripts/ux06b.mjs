import { launchChrome, Browser, login, sleep } from './cdp.mjs';
import { MEASURE } from './checks.mjs';
import { writeFileSync } from 'node:fs';
const STUB = '/home/stevdrey/qa121/stub'; const W = (f, v) => writeFileSync(`${STUB}/${f}`, v);
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
const log = []; const L = (s) => { console.log(s); log.push(s); }; let pass = 0, fail = 0; const chk = (n, ok, extra = '') => { ok ? pass++ : fail++; L(`${ok ? 'PASS' : 'FAIL'}  ${n}${extra ? '  -- ' + extra : ''}`); };
await b.send('Browser.grantPermissions', { origin: 'http://localhost:5173', permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] });
const cps = (s) => [...s].length; const unit = 'Hola ☕🇨🇱👨‍👩‍👧‍👦 línea\n'; let body = ''; while (cps(body + unit) <= 1000) body += unit; while (cps(body) < 1000) body += '🇨🇱'.length ? 'é' : ''; body = [...body].slice(0, 1000).join('');
const longRat = 'A'.repeat(480); const longWarn = 'W'.repeat(150);
W('fail', ''); W('delay.ms', '0');
W('rec.json', JSON.stringify({ recommendation: { outcome: 'ACTION', action: 'DORMANT_REENGAGEMENT', templateIntent: 'DORMANT_CUSTOMER', rationale: longRat, confidence: 0.8, draftVariables: [] } }));
W('draft.json', JSON.stringify({ draft: { outcome: 'DRAFT', action: 'DORMANT_REENGAGEMENT', templateIntent: 'DORMANT_CUSTOMER', body, draftVariables: [], locale: 'es-419', evidence: ['Producto: Té verde 500 g'], warnings: [longWarn], rationale: 'Reactivar.', confidence: 0.9 } }));
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
L(`== UX-06 AI-text stress via the FAULT-INJECTION stub (long unbroken rationale ${cps(longRat)} chars, 1000-code-point multiline body with emoji/ZWJ/flags, 150-char unbroken warning)`);
for (const [w, h] of (process.env.NARROW ? [[390, 844], [320, 568]] : [[1440, 900], [390, 844], [320, 568]])) {
  await p.viewport(w, h); await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(900); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
  await p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,'Dormant');i.dispatchEvent(new Event('input',{bubbles:true}))})()`); await sleep(1100); await p.clickText('Dormant Té QA', '*'); await sleep(900);
  await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")', 20000); await p.clickText('Generar borrador'); await p.waitFor('!!document.querySelector("textarea")', 20000); await sleep(600);
  const m = await p.ev(MEASURE); const val = await p.ev('document.querySelector("textarea").value'); const counter = await p.ev(`(document.body.innerText.match(/\\d+ \\/ 1000/)||[''])[0]`);
  chk(`${w}px: no horizontal overflow with the long AI text`, !m.horizontalOverflow && m.beyondViewport.length === 0, `docScrollW=${m.docScrollW}/${m.vw} beyond=${m.beyondViewport.slice(0, 2).join(';')}`);
  chk(`${w}px: editor holds the full 1000-code-point body and the counter says 1000 / 1000`, cps(val) === 1000 && counter === '1000 / 1000', `${cps(val)} cps (${val.length} UTF-16), counter "${counter}"`);
  const rr = await p.ev(`(()=>{const e=[...document.querySelectorAll('p,span,div')].find(x=>x.children.length===0&&x.textContent.startsWith('AAAAAAAA'));if(!e)return null;const r=e.getBoundingClientRect();return {right:Math.round(r.right),vw:innerWidth,wrap:getComputedStyle(e).overflowWrap}})()`); chk(`${w}px: the 480-char unbroken rationale stays inside the viewport`, rr && rr.right <= rr.vw, JSON.stringify(rr));
  await p.clickText('Copiar borrador'); await sleep(500); const clip = await p.ev('navigator.clipboard.readText()'); chk(`${w}px: Copiar copies the exact 1000-code-point text (newlines, emoji, ZWJ, flags intact)`, clip === val && cps(clip) === 1000);
  await p.ev(`(()=>{const t=document.querySelector('textarea');t.focus();t.setSelectionRange(t.value.length,t.value.length)})()`); await p.type('ZZZ'); const v2 = await p.ev('document.querySelector("textarea").value'); chk(`${w}px: typing at the limit does not exceed 1000 code points or split a surrogate pair`, cps(v2) === 1000 && !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(v2), `${cps(v2)} cps`);
  await p.shot(`/home/stevdrey/qa122/shots/ux06b-stub-${w}.png`);
}
L(`PASS=${pass} FAIL=${fail}`); writeFileSync('/home/stevdrey/qa122/out/ux06b-aitext-stub.txt', log.join('\n')); c.proc.kill(); process.exit(0);
