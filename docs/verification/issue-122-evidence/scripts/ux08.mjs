import { launchChrome, Browser, login, sleep, FOCUS } from './cdp.mjs';
import { writeFileSync } from 'node:fs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init(); const p2 = await (await b.newPage()).init();
const log = []; const L = (s) => { console.log(s); log.push(s); }; let pass = 0, fail = 0; const chk = (n, ok, extra = '') => { ok ? pass++ : fail++; L(`${ok ? 'PASS' : 'FAIL'}  ${n}${extra ? '  -- ' + extra : ''}`); };
await b.send('Browser.grantPermissions', { origin: 'http://localhost:5173', permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] });
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
const search = (q) => p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,${JSON.stringify(q)});i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
const API = (pg, method, path, body, hdrs = {}) => pg.ev(`(async()=>{const s=await (await fetch('/api/session')).json();const t=await (await fetch('/api/tenants')).json();const tid=sessionStorage.getItem(Object.keys(sessionStorage).find(k=>k.startsWith('dokene_active_tenant_id'))||'')||t[0].tenantId;const r=await fetch(${JSON.stringify(path)},{method:${JSON.stringify(method)},headers:{'X-Tenant-Id':tid,'X-CSRF-TOKEN':s.csrfToken,'Content-Type':'application/json',...${JSON.stringify(hdrs)}},body:${body ? JSON.stringify(JSON.stringify(body)) : 'undefined'}});return {status:r.status,etag:r.headers.get('etag'),text:(await r.text()).slice(0,300)}})()`);
const panel = () => p.ev(`(()=>{const h=[...document.querySelectorAll('h3')].find(e=>e.textContent.includes('Asistente IA'));return h?h.closest('section').innerText.replace(/\\s+/g,' '):''})()`);
L('== UX-08 stale edited draft (Chrome 155, live OpenAI for recommendation/draft; queue-refresh failure is FAULT-INJECTED by wrapping window.fetch)');
await search('Dormant'); await sleep(1300); await p.clickText('Dormant Té QA', '*'); await sleep(1200);
const cust = await p.ev(`(async()=>{const t=await (await fetch('/api/tenants')).json();return t[0].tenantId})()`);
await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")', 45000); await sleep(500);
await p.clickText('Generar borrador'); await p.waitFor('!!document.querySelector("textarea")', 45000); await sleep(500);
const original = await p.ev('document.querySelector("textarea").value'); L('original draft: ' + JSON.stringify(original.slice(0, 120)));
await p.ev(`(()=>{const t=document.querySelector('textarea');t.focus();t.setSelectionRange(t.value.length,t.value.length)})()`); await p.type(' EDICIÓN-QA-122'); const edited = await p.ev('document.querySelector("textarea").value'); chk('operator edit is in the editor', edited.includes('EDICIÓN-QA-122'));
// change policy in "another tab"
await p2.viewport(1200, 800); await p2.goto('http://localhost:5173/'); await p2.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(800);
const custId = await p2.ev(`(async()=>{const t=await (await fetch('/api/tenants')).json();const tid=sessionStorage.getItem(Object.keys(sessionStorage).find(k=>k.startsWith('dokene_active_tenant_id'))||'')||t[0].tenantId;const j=await (await fetch('/api/follow-up-queue?limit=50',{headers:{'X-Tenant-Id':tid}})).json();return (j.items||[]).find(i=>/Dormant Té QA/.test(i.displayName||i.customerName||JSON.stringify(i)))?.customerId})()`);
const pol = await API(p2, 'GET', `/api/customers/${custId}/follow-up-policy`); const et = (pol.etag || '').replace(/"/g, ''); L(`other tab: policy etag ${et}, changing cadence`);
const put = await API(p2, 'PUT', `/api/customers/${custId}/follow-up-policy`, { cadenceDays: 21 + Number(et) % 5 }, { 'If-Match': `"${et}"` }); chk('policy changed in the other tab', put.status === 200, `status ${put.status}`);
L('-- arm ONE queue refresh failure (FAULT INJECTION), then regenerate the draft in the first tab (stale ETag)');
await p.ev(`(()=>{const f=window.fetch;window.__failQueue=1;window.fetch=function(u,o){const url=typeof u==='string'?u:(u.url||String(u));if(window.__failQueue>0&&url.includes('follow-up-queue')){window.__failQueue--;window.__injected=(window.__injected||0)+1;return Promise.reject(new TypeError('Failed to fetch (injected)'))}return f.apply(this,arguments)}})()`);
await p.clickText('Regenerar borrador'); await sleep(800); L('inline confirm text: ' + await p.ev(`(document.body.innerText.match(/Regenerar reemplazará[^\\n]*/)||['(none)'])[0]`)); await p.clickText('Reemplazar mis cambios'); await sleep(3500); let pt = await panel(); L('panel text after the stale event: ' + pt.slice(0, 800));
L('injected failures: ' + await p.ev('window.__injected||0')); L('dialog open: ' + await p.ev('!!document.querySelector("[role=dialog],[aria-modal=true]")'));
chk('stale: previous recommendation text is hidden', !/Reactivación de cliente inactivo|Confianza del modelo/.test(pt));
const tx = await p.ev('Array.from(document.querySelectorAll("textarea")).map(t=>t.value)'); chk('stale: edited text remains in an editable area', tx.some((v) => v.includes('EDICIÓN-QA-122')), JSON.stringify(tx.map((v) => v.slice(-30))));
chk('stale: panel says the list refresh failed', /No pudimos actualizar/i.test(pt)); chk('stale: offers "Reintentar actualizar lista" instead of "Actualizar recomendación"', /Reintentar actualizar lista/.test(pt) && !/Actualizar recomendación/.test(pt));
await p.shot('/home/stevdrey/qa122/shots/ux08-1-stale-refresh-failed.png');
try { await p.clickText('Copiar'); await sleep(500); const clip = await p.ev('navigator.clipboard.readText()'); chk('copy works while stale and copies the edited text', clip.includes('EDICIÓN-QA-122')); } catch (e) { chk('copy while stale', false, e.message); }
L('buttons: ' + JSON.stringify(await p.ev(`[...document.querySelectorAll('button')].filter(b=>b.offsetParent!==null).map(b=>b.textContent.replace(/\\s+/g,' ').trim()).filter(t=>/Actualizar|Reintentar|Descartar|Regenerar|Copiar|Consultar|Obtener/.test(t))`)));
await p.clickText('Reintentar actualizar lista'); await sleep(2500); pt = await panel(); L('panel after retry: ' + pt.slice(0, 600));
chk('retry succeeded: panel offers "Actualizar recomendación"', /Actualizar recomendación/.test(pt)); chk('edited text still present after the successful refresh', (await p.ev('Array.from(document.querySelectorAll("textarea")).some(t=>t.value.includes("EDICIÓN-QA-122"))')) || pt.includes('EDICIÓN-QA-122'));
await p.shot('/home/stevdrey/qa122/shots/ux08-2-after-retry.png');

L('-- explicit confirmation before discarding edits'); await p.clickText('Actualizar recomendación'); await sleep(1200);
const d2 = await p.ev('!!document.querySelector("[role=dialog],[aria-modal=true]")'); const ptxt = await p.ev(`(document.querySelector('[role=dialog],[aria-modal=true]')||{innerText:document.body.innerText}).innerText.replace(/\\s+/g,' ').slice(0,400)`); L('after "Actualizar recomendación": dialog=' + d2 + ' | ' + ptxt.slice(0, 300));

await p.shot('/home/stevdrey/qa122/shots/ux08-3-confirm.png');
const conf = await p.ev(`[...document.querySelectorAll('button')].filter(b=>b.offsetParent!==null).map(b=>b.textContent.replace(/\\s+/g,' ').trim()).filter(t=>/Descartar|Confirmar|Sí|Cancelar|Mantener|Seguir/.test(t))`); L('confirmation buttons: ' + JSON.stringify(conf));
// cancel first: edited text must survive
chk('discarding edits requires an explicit inline confirmation ("Descartar y consultar" / "Cancelar") before any new request', conf.some((t) => /Descartar y consultar/.test(t)) && conf.some((t) => t === 'Cancelar'));
const cancel = conf.find((t) => /Cancelar|Mantener|Seguir/.test(t)); if (cancel) { await p.clickText(cancel); await sleep(700); chk('cancelling the confirmation keeps the edited text', await p.ev('Array.from(document.querySelectorAll("textarea")).some(t=>t.value.includes("EDICIÓN-QA-122"))') || (await panel()).includes('EDICIÓN-QA-122')); }
await p.clickText('Actualizar recomendación'); await sleep(900); const ok = (await p.ev(`[...document.querySelectorAll('button')].filter(b=>b.offsetParent!==null).map(b=>b.textContent.replace(/\\s+/g,' ').trim()).find(t=>/Descartar y|Sí, |Confirmar|Descartar ediciones|Descartar cambios/.test(t))`)); L('confirm button used: ' + ok); if (ok) { await p.clickText(ok); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")', 45000); await sleep(600); pt = await panel(); chk('after confirming, a NEW live recommendation is shown and the edited text is gone', !pt.includes('EDICIÓN-QA-122') && /Recomendación de la IA/.test(pt)); }
await p.shot('/home/stevdrey/qa122/shots/ux08-4-new-recommendation.png');
L(`PASS=${pass} FAIL=${fail}`); writeFileSync('/home/stevdrey/qa122/out/ux08-stale-chrome.txt', log.join('\n')); c.proc.kill(); process.exit(0);
