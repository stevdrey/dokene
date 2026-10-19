import { launchChrome, Browser, login, sleep } from './cdp.mjs';
import { MEASURE } from './checks.mjs';
import { writeFileSync } from 'node:fs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
const log = []; const L = (s) => { console.log(s); log.push(s); };
await b.send('Browser.grantPermissions', { origin: 'http://localhost:5173', permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] });
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
const search = (q) => p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,${JSON.stringify(q)});i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
const M = async (tag) => { const m = await p.ev(MEASURE); L(`  ${tag}: docScrollW=${m.docScrollW}/${m.vw} overflow=${m.horizontalOverflow} beyond=${m.beyondViewport.length} ${m.beyondViewport.slice(0, 2).join(';')} clipped=${m.clippedText.length}`); return m; };
L('== UX-06 content stress (live OpenAI for the AI text; fixtures are labelled synthetic UI data)');
for (const [w, h, mob] of (process.env.LIVE_ONLY ? [] : [[1440, 900, false], [390, 844, false]])) {
  L(`-- viewport ${w}x${h}`); await p.viewport(w, h); await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(1000); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
  for (const [name, q] of [['unbroken 70-char name + 180-char unbroken note/purchase', 'Wolfeschlegel'], ['emoji/ZWJ/flag name + multiline notes + HTML-ish purchase text', 'Emoji'], ['long purchase description', 'Nombre Normal Multil']]) {
    await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(900); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
    await search(q); await sleep(1300); const present = await p.ev(`document.body.innerText.includes(${JSON.stringify(q.slice(0, 9))})`); if (!present) { L(`  ${name}: not in loaded page`); continue; }
    await M(name + ' / queue filtered'); const el = await p.ev(`(()=>{const e=[...document.querySelectorAll('.follow-up-card,[role=button]')].find(x=>x.textContent.includes(${JSON.stringify(q.slice(0, 9))}));if(!e)return false;e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`); if (el) { await p.mouse(el.x, el.y); await sleep(1200); }
    await M(name + ' / detail'); await p.shot(`/home/stevdrey/qa122/shots/ux06-${w}-${q.slice(0, 6).replace(/\W/g, '')}-detail.png`);
  }
}
L('-- live AI on the emoji customer at 1440x900: recommendation, draft, counter, copy');
await p.viewport(1440, 900); await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(1000); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
await search('Ñandú V1'); await sleep(1300); const e2 = await p.ev(`(()=>{const e=[...document.querySelectorAll('.follow-up-card,[role=button]')].find(x=>x.textContent.includes('Ñandú V1'));const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`); await p.mouse(e2.x, e2.y); await sleep(1200);
await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")||document.body.innerText.includes("no está disponible")', 45000); await sleep(500);
await p.clickText('Generar borrador'); await p.waitFor('!!document.querySelector("textarea")', 45000); await sleep(500);
const val = await p.ev('document.querySelector("textarea").value'); L('  live draft: ' + JSON.stringify(val.slice(0, 160)));
// type emoji/ZWJ/flag/combining content to reach limits and check counter in code points
const stress = '☕🇨🇱👨‍👩‍👧‍👦é̃ñ';
await p.ev(`(()=>{const t=document.querySelector('textarea');t.focus();t.select()})()`); await p.type(stress.repeat(60));
const cps = (s) => [...s].length; const v2 = await p.ev('document.querySelector("textarea").value'); const counter = await p.ev(`(document.body.innerText.match(/\\d+ \\/ 1000/)||[''])[0]`);
L(`  typed ${cps(stress.repeat(60))} code points of emoji/ZWJ/flag/combining text; textarea holds ${cps(v2)} code points (${v2.length} UTF-16 units); counter shows "${counter}"; first/last code points intact: ${[...v2].slice(0, 3).join('') === [...stress.repeat(60)].slice(0, 3).join('')}`);
const lone = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(v2); L('  lone surrogate in editor value: ' + lone);
await p.clickText('Copiar borrador'); await sleep(700); const clip = await p.ev('navigator.clipboard.readText()'); L(`  clipboard after Copiar: equals editor value = ${clip === v2} (${cps(clip)} code points), toast/feedback: ${await p.ev(`(document.body.innerText.match(/copiad[oa][^\\n]*/i)||['(none)'])[0]`)}`);
await p.shot('/home/stevdrey/qa122/shots/ux06-emoji-draft.png');
writeFileSync('/home/stevdrey/qa122/out/ux06-stress.txt', log.join('\n')); c.proc.kill(); process.exit(0);
