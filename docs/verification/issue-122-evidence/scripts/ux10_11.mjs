import { launchChrome, Browser, login, sleep } from './cdp.mjs';
import { writeFileSync } from 'node:fs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init(); const p2 = await (await b.newPage()).init();
const log = []; const L = (s) => { console.log(s); log.push(s); }; let pass = 0, fail = 0; const chk = (n, ok, extra = '') => { ok ? pass++ : fail++; L(`${ok ? 'PASS' : 'FAIL'}  ${n}${extra ? '  -- ' + extra : ''}`); };
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await sleep(600);
const search = (q) => p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');if(!i)return;const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,${JSON.stringify(q)});i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
const TID = `(async()=>{const t=await (await fetch('/api/tenants')).json();return sessionStorage.getItem(Object.keys(sessionStorage).find(k=>k.startsWith('dokene_active_tenant_id'))||'')||t[0].tenantId})()`;
const API = (pg, method, path, body, hdrs = {}) => pg.ev(`(async()=>{const s=await (await fetch('/api/session')).json();const tid=await ${TID};const r=await fetch(${JSON.stringify(path)},{method:${JSON.stringify(method)},headers:{'X-Tenant-Id':tid,'X-CSRF-TOKEN':s.csrfToken,'Content-Type':'application/json',...${JSON.stringify(hdrs)}},body:${body ? JSON.stringify(JSON.stringify(body)) : 'undefined'}});return {status:r.status,etag:r.headers.get('etag'),json:await r.json().catch(()=>null)}})()`);
const selected = () => p.ev(`(document.querySelector('main h2')||{}).textContent||''`); const cards = () => p.ev(`[...document.querySelectorAll('.follow-up-card')].map(e=>e.querySelector('h3')?.textContent.trim())`);
const panel = () => p.ev(`(()=>{const h=[...document.querySelectorAll('h3')].find(e=>e.textContent.includes('Asistente IA'));return h?h.closest('section').innerText.replace(/\\s+/g,' '):''})()`);
const bodyText = () => p.ev(`document.body.innerText.replace(/\\s+/g,' ')`);
await p2.viewport(1200, 800); await p2.goto('http://localhost:5173/'); await p2.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(600);
async function custId(name) { return p2.ev(`(async()=>{const tid=await ${TID};let cur='';for(;;){const j=await (await fetch('/api/follow-up-queue?limit=50'+(cur?'&cursor='+cur:''),{headers:{'X-Tenant-Id':tid}})).json();const m=j.items.find(i=>i.displayName.startsWith(${JSON.stringify(name)}));if(m)return m.customerId;cur=j.nextCursor;if(!cur)return null}})()`); }
async function revokeConsent(id) { const cu = await API(p2, 'GET', `/api/customers/${id}`); const pid = cu.json.phones[0].id; const cp = await API(p2, 'GET', `/api/customers/${id}/contact-policy`); return API(p2, 'PUT', `/api/customers/${id}/contacts/${pid}/consents/WHATSAPP`, { status: 'REVOKED', source: 'CUSTOMER_VERBAL' }, { 'If-Match': `"${cp.json.version}"` }); }
L('== UX-10 ineligibility notice (consent revoked without a policy-version change; Chrome 155, live OpenAI)');
for (const variant of (process.env.ONLY_UX10 ? ['success'] : ['success', 'refresh-failure'])) {
  const name = variant === 'success' ? 'UX10 Fresh' : 'Chars Test C4';
  L(`-- variant: queue refresh ${variant}, customer "${name}…"`);
  await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(900); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
  await search(name); await sleep(1300); const nm = (await cards())[0]; if (!nm) { L('  customer not in the loaded page'); continue; } await p.clickText(nm, '*'); await sleep(1200);
  await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")', 45000); await sleep(500);
  const id = await custId(name); const rv = await revokeConsent(id); chk(`${variant}: consent revoked in the other tab`, rv.status === 200, `status ${rv.status}`);
  const pv0 = (await API(p2, 'GET', `/api/customers/${id}/follow-up-policy`)).etag;
  if (variant === 'refresh-failure') await p.ev(`(()=>{const f=window.fetch;window.__failQueue=1;window.fetch=function(u,o){const url=typeof u==='string'?u:(u.url||String(u));if(window.__failQueue>0&&url.includes('follow-up-queue')){window.__failQueue--;return Promise.reject(new TypeError('Failed to fetch (injected)'))}return f.apply(this,arguments)}})()`);
  await p.clickText('Consultar de nuevo'); await sleep(3500); let pt = await bodyText(); const pn = await panel();
  L(`  policy ETag unchanged by the revoke: ${pv0}; panel: ${pn.slice(0, 420)}`);
  const notice = new RegExp(nm.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '[^.]*ya no es elegible').test(pt);
  if (variant === 'success') {
    chk('notice names the customer and says the list was updated, after the successful refresh', notice && /La lista se ha actualizado/.test(pt));
    const sel = await selected(); const cs = await cards(); chk('automatic selection fallback: the ineligible customer left the list', !cs.includes(nm), `now selected: "${sel}"`);
    chk('notice survives the automatic selection fallback', notice, `selected now: ${sel}`);
    await p.shot('/home/stevdrey/qa122/shots/ux10-1-notice.png');
    // clear by the operator's own action: clear the search and select another customer
    try { await p.clickText('Limpiar búsqueda', 'button'); await sleep(1200); } catch (e) { await search(''); await sleep(1200); }
    pt = await bodyText(); L('  after clearing the search the notice is ' + (/ya no es elegible para seguimiento/.test(pt) ? 'still shown' : 'gone'));
    const other = (await cards())[0]; if (other) { await p.clickText(other, '*'); await sleep(900); pt = await bodyText(); chk('notice clears when the operator selects another customer', !/ya no es elegible para seguimiento/.test(pt)); }
  } else {
    chk('refresh failed: panel says the list could not be updated and offers "Reintentar actualizar lista"', /No pudimos actualizar la lista/.test(pt) && /Reintentar actualizar lista/.test(pt));
    chk('refresh failed: the "ya no es elegible… se ha actualizado" notice is NOT shown yet', !/La lista se ha actualizado/.test(pt));
    await p.shot('/home/stevdrey/qa122/shots/ux10-2-refresh-failed.png'); await p.clickText('Reintentar actualizar lista'); await sleep(2500); pt = await bodyText();
    chk('after a successful retry the customer notice appears', /ya no es elegible/.test(pt) && /La lista se ha actualizado/.test(pt));
  }
}
if (process.env.ONLY_UX10) { L(`PASS=${pass} FAIL=${fail}`); writeFileSync('/home/stevdrey/qa122/out/ux10-only.txt', log.join('\n')); c.proc.kill(); process.exit(0); }
L('== UX-11 navigation and races (live OpenAI; the in-flight result is the real provider call)');
await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(900); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
await search('Dormant'); await sleep(1200); await p.clickText('Dormant Té QA', '*'); await sleep(1000);
L('-- (a) workspace change while a recommendation is pending');
await p.clickText('Obtener recomendación'); await sleep(250); const wsOpen = await p.ev(`(()=>{const b=[...document.querySelectorAll('button')].find(x=>x.getAttribute('aria-label')==='Seleccionar espacio de trabajo'||x.textContent.includes('QA Café Norte'));b.click();return !!b})()`); await sleep(500);
const opts = await p.ev(`[...document.querySelectorAll('[role=option],[role=menuitem],li button,[role=listbox] *')].map(e=>e.textContent.trim()).filter(t=>/QA Café/.test(t))`); L('workspace options: ' + JSON.stringify([...new Set(opts)]));
await p.clickText('QA Café Sur', '*'); await sleep(3500); const t1 = await bodyText();
chk('after switching workspace no content of the first workspace/customer remains', !/Dormant Té QA/.test(t1) && !/Recomendación de la IA/.test(t1), (await selected()) || '(no selection)'); chk('no AI panel text from the previous tenant', !/Reactivación de cliente inactivo/.test(t1));
const tidNow = await p.ev(TID); L('active workspace now: ' + tidNow);
await p.shot('/home/stevdrey/qa122/shots/ux11-a-after-workspace-switch.png');
await p.clickText('QA Café Sur', 'button'); await sleep(400); await p.clickText('QA Café Norte', '*'); await sleep(2500);
L('-- (b) logout while a recommendation is pending');
await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await search('Dormant'); await sleep(1200); await p.clickText('Dormant Té QA', '*'); await sleep(900); await p.clickText('Obtener recomendación'); await sleep(250);
await p.clickText('Cerrar sesión'); await sleep(3500); const t2 = await bodyText(); chk('after logout during a pending request: login screen, no advice shown', /Iniciar sesión/.test(t2) && !/Recomendación de la IA/.test(t2), t2.slice(0, 80));
L('-- (c) browser Back / Forward'); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await sleep(500);
await search('Dormant'); await sleep(1100); await p.clickText('Dormant Té QA', '*'); await sleep(900); await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")', 45000);
const h0 = await p.ev('history.length+" "+location.href'); await p.clickText('Clientes', 'button,a'); await sleep(1200); const h1 = await p.ev('history.length+" "+location.href'); L(`history after navigating to Clientes: before=${h0} after=${h1} (SPA state-based navigation: the URL does not change)`);
await p.ev('history.back()'); await sleep(2000); const url1 = await p.ev('location.href'); const t3 = await bodyText(); L(`after Back: url=${url1} | text starts: ${t3.slice(0, 90)}`);
L('   Back leaves the application page (no in-app history entry) ' + (/localhost:5173/.test(url1) ? 'and stays in the app' : '-> navigated away from the app'));
await p.ev('history.forward()'); await sleep(2000); const url2 = await p.ev('location.href'); const t4 = await bodyText(); L(`after Forward: url=${url2} | session intact: ${!/Iniciar sesión con OIDC/.test(t4)} | text: ${t4.slice(0, 90)}`);
await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(900); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
L('-- (d) crossing the responsive breakpoint (1440 -> 900 -> 390 -> 1440) with a draft being edited');
await search('Dormant'); await sleep(1100); await p.clickText('Dormant Té QA', '*'); await sleep(900); await p.clickText('Obtener recomendación'); await p.waitFor('document.body.innerText.includes("Recomendación de la IA")', 45000); await p.clickText('Generar borrador'); await p.waitFor('!!document.querySelector("textarea")', 45000); await sleep(400);
await p.ev(`(()=>{const t=document.querySelector('textarea');t.focus();t.setSelectionRange(t.value.length,t.value.length)})()`); await p.type(' CRUCE-BREAKPOINT');
const v0 = await p.ev('document.querySelector("textarea").value'); chk('edited text present at 1440', v0.includes('CRUCE-BREAKPOINT'));
for (const [w, h] of [[900, 800], [390, 844], [1440, 900]]) { await p.viewport(w, h); await sleep(1200); const ta = await p.ev('(document.querySelector("textarea")||{}).value||null'); const pt = await panel(); L(`   at ${w}px: editor value kept=${ta ? ta.includes('CRUCE-BREAKPOINT') : false}; panel mentions: ${pt.slice(0, 160)}; selected: ${(await selected()) || '(none)'}`); await p.shot(`/home/stevdrey/qa122/shots/ux11-d-${w}.png`); }
L(`PASS=${pass} FAIL=${fail}`); writeFileSync('/home/stevdrey/qa122/out/ux10-11-chrome.txt', log.join('\n')); c.proc.kill(); process.exit(0);
