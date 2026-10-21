import { launchChrome, Browser, login, sleep } from './cdp.mjs';
import { MEASURE } from './checks.mjs';
import { writeFileSync } from 'node:fs';
const VPS = [[1440, 900, 'desktop', false], [1024, 768, 'laptop', false], [768, 1024, 'tablet', false], [390, 844, 'phone', true], [320, 568, 'small-phone', true]];
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
const results = { browser: c.version, sha: process.env.SHA, rows: [] };
await p.viewport(1440, 900); await login(p);
async function snap(vpName, stage) { const m = await p.ev(MEASURE); await p.shot(`/home/stevdrey/qa122/shots/ux01-${vpName}-${stage}.png`); results.rows.push({ vp: vpName, stage, ...m }); const flag = m.horizontalOverflow || m.beyondViewport.length || m.clippedText.length; console.log(`${vpName} ${stage}: overflow=${m.horizontalOverflow} beyond=${m.beyondViewport.length} clipped=${m.clippedText.length} small<24=${m.smallTargets.length} contrastFails=${m.contrastFailCount}/${m.contrastChecked} unnamed=${m.unnamed.length}${flag ? '  <-- check' : ''}`); }
for (const [w, h, name, mobile] of VPS) {
  console.log('=== ' + name + ' ' + w + 'x' + h);
  await p.viewport(w, h, { mobile }); await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(1500);
  await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await sleep(500);
  await snap(name, '1-queue');
  await p.ev(`(()=>{const i=document.querySelector('input[type=search],input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,'Dormant');i.dispatchEvent(new Event('input',{bubbles:true}))})()`); await sleep(1500); await snap(name, '1b-queue-filtered');
  try { await p.clickText('Dormant Té QA', '*'); await sleep(1500); } catch (e) { console.log('select failed', e.message); }
  await snap(name, '2-selected');
  try { await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")||document.body.innerText.includes("no está disponible")', 40000); await sleep(600); } catch (e) { console.log('rec failed', e.message); }
  await snap(name, '3-recommendation');
  try { await p.clickText('Generar borrador'); await p.waitFor('document.querySelector("textarea")', 40000); await sleep(600); } catch (e) { console.log('draft failed', e.message); }
  await snap(name, '4-draft');
  // customers screen
  try { await p.clickText('Clientes', 'a,button'); await sleep(1500); } catch (e) { try { await p.clickText('Clientes', '*'); await sleep(1500); } catch (e2) { console.log('nav failed', e2.message); } }
  await snap(name, '5-customers');
  try { await p.clickText('Nuevo', 'button'); await sleep(800); await snap(name, '6-newcustomer-modal'); await p.key('Escape'); await sleep(400); } catch (e) { console.log('modal failed', e.message); }
}
writeFileSync('/home/stevdrey/qa122/out/ux01-chrome.json', JSON.stringify(results, null, 1));
c.proc.kill(); process.exit(0);
