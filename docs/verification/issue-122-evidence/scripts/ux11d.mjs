import { launchChrome, Browser, login, sleep } from './cdp.mjs';
import { writeFileSync } from 'node:fs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init(); const log = []; const L = (s) => { console.log(s); log.push(s); };
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await sleep(500);
const search = (q) => p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,${JSON.stringify(q)});i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
await search('Dormant'); await sleep(1200); await p.clickText('Dormant Té QA', '*'); await sleep(900); await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")', 45000);
await p.ev('window.__marker=12345'); await p.ev(`(()=>{window.__renders=0;const t=document.querySelector('main');window.__node=document.querySelector('h2')})()`);
L('recommendation visible at 1440; page marker set (a reload would clear it)');
for (const [w, h] of [[1100, 800], [1023, 800], [1024, 800]]) { await p.viewport(w, h); await sleep(1200); L(`width ${w}: marker=${await p.ev('window.__marker')} (same document: ${await p.ev('window.__marker===12345')}); recommendation still shown: ${await p.ev('document.body.innerText.includes("Recomendación de la IA")')}; detail header node preserved: ${await p.ev('document.body.contains(window.__node)')}; selected: ${await p.ev(`(document.querySelector('main h2')||{}).textContent`)}`); }
writeFileSync('/home/stevdrey/qa122/out/ux11d.txt', log.join('\n')); c.proc.kill(); process.exit(0);
