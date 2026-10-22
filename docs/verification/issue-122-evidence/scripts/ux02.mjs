import { launchChrome, Browser, login, sleep } from './cdp.mjs';
import { MEASURE } from './checks.mjs';
import { writeFileSync } from 'node:fs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
const rows = []; await p.viewport(1440, 900); await login(p);
const S = async (name, stage) => { const m = await p.ev(MEASURE); await p.shot(`/home/stevdrey/qa122/shots/ux02-${name}-${stage}.png`); rows.push({ name, stage, ...m }); console.log(`${name} ${stage}: vw=${m.vw} docScrollW=${m.docScrollW} overflow=${m.horizontalOverflow} beyond=${m.beyondViewport.length} clipped=${m.clippedText.length} small<24=${m.smallTargets.length} offscreenInteractive=${m.offscreenInteractive}`); };
for (const [name, w, h, dsf] of [['zoom200', 720, 450, 2], ['zoom400', 320, 256, 4]]) {
  console.log(`=== ${name}: ${w}x${h} CSS px at devicePixelRatio ${dsf} (= ${dsf * 100}% zoom of a ${w * dsf}x${h * dsf} window)`);
  await p.viewport(w, h, { dsf }); await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(1000); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
  await p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,'Dormant');i.dispatchEvent(new Event('input',{bubbles:true}))})()`); await sleep(1200); await S(name, '1-queue');
  await p.clickText('Dormant Té QA', '*'); await sleep(1200); await S(name, '2-selected');
  await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")||document.body.innerText.includes("no está disponible")', 45000); await sleep(500); await S(name, '3-recommendation');
  await p.clickText('Generar borrador'); await p.waitFor('!!document.querySelector("textarea")', 45000); await sleep(500); await S(name, '4-draft');
  await p.ev(`document.querySelector('textarea').scrollIntoView({block:'center'})`); await sleep(300); await S(name, '4b-editor');
  await p.clickText('Posponer'); await sleep(700); await S(name, '5-modal-posponer'); await p.key('Escape'); await sleep(400);
  await p.clickText('Descartar'); await sleep(700); await S(name, '6-modal-descartar'); await p.key('Escape'); await sleep(400);
}
writeFileSync('/home/stevdrey/qa122/out/ux02-zoom-chrome.json', JSON.stringify(rows, null, 1)); c.proc.kill(); process.exit(0);
