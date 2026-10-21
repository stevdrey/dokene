// QA suite #123 RES-07: races in the browser while a provider call is in flight (injected 6 s provider latency through the stub). Chrome 155 via /home/stevdrey/qa122/cdp.mjs.
import { launchChrome, Browser, login, sleep } from '/home/stevdrey/qa122/cdp.mjs';
import { execSync } from 'node:child_process';
import { writeFileSync, readFileSync } from 'node:fs';
const QA = '/home/stevdrey/Projects/Java/dokene-qa-run'; const ST = '/home/stevdrey/qa121/stub';
const sh = (c) => execSync(c, { cwd: QA, encoding: 'utf8', shell: '/bin/bash' }).trim();
const nreq = () => readFileSync(`${ST}/requests.log`, 'utf8').split('\n').filter(Boolean).length;
const sql = (q) => execSync("docker compose exec -T postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -At'", { cwd: QA, encoding: 'utf8', input: q, shell: '/bin/bash' }).trim();
const audit = () => Number(sql("select count(*) from dokene.audit_events where event_type='AI_INVOCATION_OUTCOME';"));
const lastAudit = () => sql("select ai_operation||'/'||ai_outcome||'/'||ai_detail from dokene.audit_events where event_type='AI_INVOCATION_OUTCOME' order by occurred_at desc limit 1;");
const W = (f, v) => writeFileSync(`${ST}/${f}`, v);
const log = []; const L = (s) => { console.log(s); log.push(s); }; let pass = 0, fail = 0; const chk = (n, ok, extra = '') => { ok ? pass++ : fail++; L(`${ok ? 'PASS' : 'FAIL'}  ${n}${extra ? '  -- ' + extra : ''}`); };
W('fail', ''); W('delay.ms', '0'); W('delay-ok.ms', '6000');
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
const safeJson = (v) => JSON.stringify(v).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
const search = (q) => p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');if(!i)return;const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,${safeJson(q)});i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
const open = async () => { await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(900); if (await p.ev(`!!document.querySelector('aside')&&/QA Café Sur/.test(document.querySelector('aside').innerText.split('RELACIONES')[0])`)) { await p.ev(`[...document.querySelectorAll('button')].find(x=>x.getAttribute('aria-label')==='Seleccionar espacio de trabajo').click()`); await sleep(500); await p.clickText('QA Café Norte', '*'); await sleep(2500); } await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await search('Dormant'); await sleep(1100); await p.clickText('Dormant Té QA', '*'); await sleep(900); };
const text = () => p.ev(`document.body.innerText.replace(/\\s+/g,' ')`);
const settle = async () => { await sleep(8000); };
L('== RES-07 UI races with a provider that answers after 6 s (injected); the provider work is observed from the stub log and the durable audit');
// a) double activation
await open(); let n0 = nreq(), a0 = audit(); const pos = await p.ev(`(()=>{const e=[...document.querySelectorAll('button')].find(b=>b.textContent.includes('Obtener recomendación'));const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`);
await p.mouse(pos.x, pos.y); await p.mouse(pos.x, pos.y); await p.ev(`document.querySelector('button')&&0`); await p.key('Enter'); await p.key('Enter'); await settle(); await sleep(1500);
chk('a) double click and double Enter produce exactly one provider request and one audit row', nreq() - n0 === 1 && audit() - a0 === 1, `provider requests ${nreq() - n0}, audit rows ${audit() - a0}`);
chk('a) the single result is shown (no duplicate panels)', (await p.ev(`[...document.querySelectorAll('h4')].filter(h=>h.textContent.includes('Recomendación de la IA')).length`)) === 1);
// b) customer switch while pending
await open(); n0 = nreq(); a0 = audit(); await p.clickText('Obtener recomendación'); await sleep(1200);
const other = await p.ev(`(()=>{const cs=[...document.querySelectorAll('.follow-up-card')];return cs.length>1?cs[1].querySelector('h3').textContent.trim():null})()`);
await search(''); await sleep(1200); const second = await p.ev(`(()=>{const cs=[...document.querySelectorAll('.follow-up-card')];return cs[1].querySelector('h3').textContent.trim()})()`); await p.clickText(second, '*'); await sleep(900);
let t = await text(); chk('b) switching customer during the call: no recommendation/advice of the previous customer appears', !/Recomendación de la IA/.test(t) && !/Reactivación/.test(t), `now showing "${second}"`);
await settle(); t = await text(); chk('b) the late response never appears for the other customer', !/Recomendación de la IA/.test(t) && !/Reactivación de cliente/.test(t));
L(`   observed: provider requests ${nreq() - n0} (completed at the provider), audit rows +${audit() - a0} (${lastAudit()})`);
// c) workspace switch while pending
await open(); n0 = nreq(); a0 = audit(); await p.clickText('Obtener recomendación'); await sleep(1200);
await p.ev(`[...document.querySelectorAll('button')].find(x=>x.getAttribute('aria-label')==='Seleccionar espacio de trabajo'||x.textContent.includes('QA Café Norte')).click()`); await sleep(500); await p.clickText('QA Café Sur', '*'); await sleep(2500); t = await text();
chk('c) switching workspace during the call: nothing from the first workspace', !/Dormant Té QA/.test(t) && !/Recomendación de la IA/.test(t));
await settle(); t = await text(); chk('c) the late response never appears in the other workspace', !/Recomendación de la IA/.test(t)); L(`   observed: provider requests ${nreq() - n0}, audit rows +${audit() - a0} (${lastAudit()})`);
// d) navigate away (reload) while pending
await open(); n0 = nreq(); a0 = audit(); await p.clickText('Obtener recomendación'); await sleep(1200); await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await settle();
t = await text(); chk('d) navigating away/reloading during the call: no stale advice after reload', !/Recomendación de la IA/.test(t)); L(`   observed: provider requests ${nreq() - n0} (the aborted browser request does not cancel the provider call: documented limitation #178/#180), audit rows +${audit() - a0} (${lastAudit()})`);
// e) logout while pending
await open(); n0 = nreq(); a0 = audit(); await p.clickText('Obtener recomendación'); await sleep(1200); await p.clickText('Cerrar sesión'); await sleep(3000); t = await text();
chk('e) logout during the call: login screen, no advice', /Iniciar sesión/.test(t) && !/Recomendación de la IA/.test(t)); await settle(); t = await text(); chk('e) late response after logout shows nothing', !/Recomendación de la IA/.test(t)); L(`   observed: provider requests ${nreq() - n0}, audit rows +${audit() - a0} (${lastAudit()})`);
// f) backend crash (SIGKILL) while the call runs, and graceful restart
await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await open(); n0 = nreq(); a0 = audit(); W('delay-ok.ms', '6000'); await p.clickText('Obtener recomendación'); await sleep(1500);
L('   f1) SIGKILL of the backend container while the call is in flight'); sh('docker kill dokene-qa-run-backend-1 >/dev/null'); await sleep(7000); t = await text(); const ai = t.indexOf('Asistente IA'); L('   UI after the crash: ' + t.slice(ai, ai + 260));
chk('f1) backend crash during the call: no false success, an understandable error with manual actions available', !/Recomendación de la IA/.test(t) && /no pudimos|no está disponible|error/i.test(t) && /Posponer/.test(t));
L(`   observed after the crash: provider requests ${nreq() - n0} (the provider call was already sent), audit rows +${audit() - a0} (no terminal audit row can exist for a killed process)`);
sh('docker compose up -d --no-deps backend >/dev/null 2>&1'); for (let i = 0; i < 60; i++) { try { if (sh("docker compose ps backend --format '{{.Status}}'").includes('healthy')) break; } catch {} await sleep(2000); }
const status = await p.ev(`(async()=>{try{const r=await fetch('/api/session');return r.status}catch(e){return 'network-error'}})()`); L(`   backend back; GET /api/session with the old cookie -> ${status} (BFF sessions live in the backend process)`);
await p.goto('http://localhost:5173/'); await sleep(1500); t = await text(); chk('f1) after the crash the old session is rejected cleanly and the login screen is shown (no partial UI)', /Iniciar sesión/.test(t) || status === 401, t.slice(0, 70));
await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); W('delay-ok.ms', '0'); await open(); await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")||document.body.innerText.includes("no está disponible")', 20000); t = await text(); chk('f1) recovery: after signing in again a new recommendation works', /Recomendación de la IA/.test(t));
L('   f2) graceful restart (SIGTERM) while the call is in flight'); W('delay-ok.ms', '6000'); await open(); n0 = nreq(); a0 = audit(); await p.clickText('Obtener recomendación'); await sleep(1500); sh('docker compose restart backend >/dev/null 2>&1'); await sleep(3000);
for (let i = 0; i < 60; i++) { try { if (sh("docker compose ps backend --format '{{.Status}}'").includes('healthy')) break; } catch {} await sleep(2000); } t = await text(); const a2 = t.indexOf('Asistente IA'); L('   UI after the graceful restart: ' + t.slice(a2, a2 + 200)); L(`   observed: provider requests ${nreq() - n0}, audit rows +${audit() - a0} (${lastAudit()}): a graceful restart lets the in-flight request finish, so a delivered result has its audit row`);
chk('f2) graceful restart: a delivered result is backed by exactly one terminal audit row (no unaudited result)', (/Recomendación de la IA/.test(t) ? audit() - a0 === 1 : true));
W('delay-ok.ms', '0');
L(`PASS=${pass} FAIL=${fail}`); writeFileSync('/home/stevdrey/qa123/out/res07.txt', log.join('\n')); c.proc.kill(); process.exit(0);
