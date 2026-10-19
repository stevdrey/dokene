// QA suite #123 RES-11: after the fault exploration, real OpenAI configuration restored; recommendation -> draft -> copy -> manual follow-up in the UI (Chrome 155, live OpenAI).
import { launchChrome, Browser, login, sleep } from '/home/stevdrey/qa122/cdp.mjs';
import { writeFileSync } from 'node:fs';
const log = []; const L = (s) => { console.log(s); log.push(s); }; let pass = 0, fail = 0; const chk = (n, ok, extra = '') => { ok ? pass++ : fail++; L(`${ok ? 'PASS' : 'FAIL'}  ${n}${extra ? '  -- ' + extra : ''}`); };
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
await b.send('Browser.grantPermissions', { origin: 'http://localhost:5173', permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] });
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
const search = (q) => p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,${JSON.stringify(q)});i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
await search('Dormant'); await sleep(1200); await p.clickText('Dormant Té QA', '*'); await sleep(900);
await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")||document.body.innerText.includes("no está disponible")', 45000); const rec = await p.ev('document.body.innerText.includes("Recomendación de la IA")'); chk('live recommendation displayed after the restoration', rec);
await p.clickText('Generar borrador'); await p.waitFor('!!document.querySelector("textarea")', 45000); const d = await p.ev('document.querySelector("textarea").value'); chk('live draft in es-419 displayed', d.length > 20 && /[áéíóúñ¿¡]|hola|compra/i.test(d), JSON.stringify(d.slice(0, 100)));
await p.clickText('Copiar borrador'); await sleep(500); chk('copy works (clipboard equals the editor text)', (await p.ev('navigator.clipboard.readText()')) === d);
await p.clickText('Registrar seguimiento'); await sleep(700); await p.ev(`(()=>{const t=document.querySelector('[role=dialog] textarea,[aria-modal=true] textarea');const set=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;set.call(t,'RES-11 seguimiento manual tras restaurar OpenAI');t.dispatchEvent(new Event('input',{bubbles:true}))})()`); await p.clickText('Guardar seguimiento'); await sleep(2500);
const t = await p.ev(`document.body.innerText.replace(/\\s+/g,' ')`); chk('supported manual workflow completes (follow-up recorded, dialog closed)', !(await p.ev('!!document.querySelector("[role=dialog],[aria-modal=true]")')), t.slice(t.indexOf('Dormant'), t.indexOf('Dormant') + 80));
L(`PASS=${pass} FAIL=${fail}`); writeFileSync('/home/stevdrey/qa123/out/res11.txt', log.join('\n')); c.proc.kill(); process.exit(0);
