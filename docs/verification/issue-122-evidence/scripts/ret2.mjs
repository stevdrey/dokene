import { launchChrome, Browser, login, sleep, FOCUS } from './cdp.mjs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
await p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,'Dormant');i.dispatchEvent(new Event('input',{bubbles:true}))})()`); await sleep(1200); await p.clickText('Dormant Té QA', '*'); await sleep(900);
await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")', 45000); await p.clickText('Generar borrador'); await p.waitFor('!!document.querySelector("textarea")', 45000); await sleep(600);
console.log('EVIDENCE:', await p.ev(`(()=>{const t=document.body.innerText;const i=t.search(/DATOS EN LOS QUE SE BASA/i);return t.slice(i,i+260).replace(/\\s+/g,' ')})()`));
await p.ev(`[...document.querySelectorAll('button')].find(b=>b.textContent.includes('Regenerar borrador')).focus()`); await p.key('Enter');
for (const ms of [100, 400, 1500, 4000, 8000]) { await sleep(ms === 100 ? 100 : ms - [0,100,400,1500,4000][[100,400,1500,4000,8000].indexOf(ms)]); const f = await p.ev(FOCUS); const loading = await p.ev(`[...document.querySelectorAll('[role=status]')].map(e=>e.textContent.trim()).join('|')`); console.log(`t+${ms}ms focus=${f.tag}${f.name ? ' "' + f.name + '"' : ''} status="${loading}" textarea=${await p.ev('!!document.querySelector("textarea")')}`); }
c.proc.kill(); process.exit(0);
