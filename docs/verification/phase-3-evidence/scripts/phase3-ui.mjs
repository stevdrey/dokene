// Issue 140 Phase 3 browser journey on the live-override stack (frontend :5174), outbound OFF everywhere.
// Synthetic customer in the live tenant; real OpenAI draft; real Graph-verified template; the send must be refused
// before any provider call. Prints PASS/FAIL lines; never prints phone numbers, tokens or the draft body.
import { launchChrome, Browser, sleep } from '/home/stevdrey/qa122/cdp.mjs';
const APP = 'http://localhost:5174';
const PW = process.env.DOKENE_TEST_USER_PASSWORD || 'testpassword';
const RUN = Date.now().toString().slice(-7);
const NAME = `QA140 Sintético ${RUN}`;
let pass = 0, fail = 0;
// JSON for values embedded in evaluated page code, with <, U+2028 and U+2029 escaped (same helper as issue 123).
const safeJson = (v) => JSON.stringify(v).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
const READY = `document.body.innerText.includes("No hay una plantilla aprobada")||[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='Enviar a aprobación'&&!b.disabled)`;
const chk = (label, ok, note = '') => { ok ? pass++ : fail++; console.log(`${ok ? 'PASS' : 'FAIL'}  P3-UI  ${label}${note ? '  -- ' + note : ''}`); };

async function open(user, port) {
  const c = await launchChrome({ port }); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
  await p.viewport(1440, 900); await p.goto(APP + '/'); await p.waitFor('document.body.innerText.length>0');
  if (await p.ev('document.body.innerText.includes("Iniciar sesión con OIDC")')) await p.clickText('Iniciar sesión', '*');
  await p.waitFor('location.host.includes("8081")||document.querySelector("main")', 15000);
  if (await p.ev('location.host.includes("8081")')) {
    await p.waitFor('document.querySelector("#username")', 10000);
    await p.ev(`document.querySelector('#username').value=${safeJson(user)};document.querySelector('#password').value=${safeJson(PW)};document.querySelector('form').submit()`);
  }
  await p.waitFor('location.host==="localhost:5174"&&document.querySelector("main")&&document.body.innerText.includes("Seguimientos")', 30000);
  await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 30000);
  return p;
}
// Click the visible button whose normalized text is exactly `text`, inside `scope` when given.
const clickExact = (p, text, scope = 'document') => p.ev(`(()=>{const n=x=>x.textContent.replace(/\\s+/g,' ').trim();const e=[...${scope}.querySelectorAll('button')].find(b=>b.offsetParent!==null&&n(b)===${safeJson(text)}&&!b.disabled);if(!e)return false;e.scrollIntoView({block:'center'});e.click();return true})()`);
const hasButton = (p, text) => p.ev(`[...document.querySelectorAll('button')].some(b=>b.offsetParent!==null&&b.textContent.replace(/\\s+/g,' ').trim()===${safeJson(text)})`);
const text = (p) => p.ev('document.body.innerText');
// Only the delivery-history section, so a status word elsewhere on the page cannot satisfy a history check.
const historyText = (p) => p.ev(`(document.querySelector('section.delivery-history')||{innerText:''}).innerText`);
// The CSRF token and the live tenant id are resolved once per page load and cached on the page.
const api = (p, method, path, body, extra = {}) => p.ev(`(async()=>{const c=window.__qa140||(window.__qa140=await (async()=>{const s=await (await fetch('/api/session')).json();const t=await (await fetch('/api/tenants')).json();return {csrf:s.csrfToken,tid:t.find(x=>x.displayName==='QA Café Norte').tenantId}})());const r=await fetch(${safeJson(path)},{method:${safeJson(method)},headers:Object.assign({'X-Tenant-Id':c.tid,'X-CSRF-TOKEN':c.csrf,'Content-Type':'application/json'},${safeJson(extra)}),body:${body ? safeJson(JSON.stringify(body)) : 'undefined'}});let j=null;try{j=await r.json()}catch{};return {status:r.status,etag:r.headers.get('etag'),body:j}})()`);

async function openCard(p) {
  await p.waitFor('!!document.querySelector(\'input[placeholder*="Buscar"]\')', 30000);
  await p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,${safeJson(NAME)});i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
  for (let i = 0; i < 8; i++) {
    await sleep(1200);
    const hit = await p.ev(`(()=>{const e=[...document.querySelectorAll('.follow-up-card')].find(x=>x.textContent.includes(${safeJson(NAME)}));if(!e)return false;e.scrollIntoView({block:'center'});e.click();return true})()`);
    if (hit) return true;
    if (!(await clickExact(p, 'Cargar más seguimientos'))) await clickExact(p, 'Cargar más');
  }
  return false;
}

// --- setup through the API as the operator: a synthetic due customer with consent and a purchase ---
const op = await open('testoperator', 9341);
const phone = '8888' + RUN.slice(-4);
const created = await api(op, 'POST', '/api/customers', { displayName: NAME, phones: [{ number: phone, region: 'CR', primary: true }] });
chk('synthetic customer created', created.status === 201, String(created.status));
const id = created.body.id, contactId = created.body.phones[0].id;
const cp = await api(op, 'GET', `/api/customers/${id}/contact-policy`);
await api(op, 'PUT', `/api/customers/${id}/contacts/${contactId}/consents/WHATSAPP`, { status: 'GRANTED', source: 'CUSTOMER_WRITTEN' }, { 'If-Match': cp.etag });
await api(op, 'POST', `/api/customers/${id}/purchases`, { purchasedAt: new Date(Date.now() - 60 * 86400e3).toISOString().replace(/\.\d+Z$/, 'Z'), description: 'Café 1 kg' }, { 'Idempotency-Key': 'qa140-' + RUN });
const fp = await api(op, 'GET', `/api/customers/${id}/follow-up-policy`);
const due = await api(op, 'PUT', `/api/customers/${id}/follow-up-policy`, { cadenceDays: 30, explicitNextDate: new Date().toISOString().slice(0, 10) }, { 'If-Match': fp.etag });
chk('customer made due today', due.status === 200, String(due.status));

// --- the journey in the workbench as the operator ---
// Cleanup state lives outside the try so the finally block can undo whatever the journey got to.
let intent = null, mid = null;
try {
await op.goto(APP + '/'); await op.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 30000);
chk('due customer found in the queue and opened', await openCard(op));
await op.waitFor('document.body.innerText.includes("Obtener recomendación")', 20000);
await clickExact(op, 'Obtener recomendación');
try { await op.waitFor('document.body.innerText.includes("Generar borrador")||document.body.innerText.includes("no está disponible")', 60000); }
catch (e) { const t = await text(op); const i = t.indexOf('Recomendación'); console.log('DEBUG ai panel: ' + t.slice(Math.max(0, i - 200), i + 600).replace(/\+?\d[\d ]{7,}/g, '<phone>').replace(/\s+/g, ' ')); throw e; }
chk('live OpenAI recommendation offers a draft', await hasButton(op, 'Generar borrador'));
await clickExact(op, 'Generar borrador');
await op.waitFor('!!document.querySelector("textarea")||document.body.innerText.includes("no está disponible")', 60000);
chk('live OpenAI draft shown in the editable panel', await op.ev('!!document.querySelector("textarea")&&document.querySelector("textarea").value.length>0'));
chk('"Revisar y enviar" is offered next to the draft', await hasButton(op, 'Revisar y enviar'));
await clickExact(op, 'Revisar y enviar');
await op.waitFor(READY, 30000);
if ((await text(op)).includes('No hay una plantilla aprobada')) {
  // Fail closed: the AI chose an intent this tenant has not mapped, so the review refuses to submit.
  chk('review refuses to submit when the chosen intent has no approved template', true);
  const templates = await api(op, 'GET', '/api/templates');
  const general = templates.body.items.find(t => t.templateIntent === 'GENERAL_FOLLOW_UP');
  const open = templates.body.items.find(t => t.mapped && t.templateIntent !== 'GENERAL_FOLLOW_UP' && !t.sendable) ||
    templates.body.items.find(t => t.templateIntent === 'REPEAT_PURCHASE');
  intent = open.templateIntent;
  const cond = open.mapped ? { 'If-Match': '"' + open.version + '"' } : { 'If-None-Match': '*' };
  const own = await open_(intent, general, cond);
  chk(`temporary ${intent} mapping verified APPROVED through Graph`, own.sendable === true, String(own.approvalStatus));
  await clickExact(op, 'Cerrar revisión'); await sleep(800);
  await clickExact(op, 'Revisar y enviar');
  try { await op.waitFor(READY, 30000); }
  catch (e) { const t = await text(op); const i = t.indexOf('Revisar y enviar'); console.log('DEBUG reopen: ' + t.slice(Math.max(0, i - 100), i + 1200).replace(/\+?\d[\d ]{7,}/g, '<phone>').replace(/\s+/g, ' ')); throw e; }
}
async function open_(intent, general, cond) {
  // The operator holds TEMPLATE_WRITE (P3-PERM-09), so the same session maps and verifies the template.
  const put = await api(op, 'PUT', `/api/templates/${intent}`, { providerTemplateName: general.providerTemplateName, language: general.language, parameterNames: [], enabled: true }, cond);
  const ref = await api(op, 'POST', `/api/templates/${intent}/approval-refresh`, null, { 'If-Match': put.etag });
  return ref.body || {};
}
await clickExact(op, 'Enviar a aprobación');
await op.waitFor('document.body.innerText.includes("Confirmar envío a aprobación")', 10000);
await clickExact(op, 'Confirmar envío a aprobación');
try { await op.waitFor('document.body.innerText.includes("Aún no se ha enviado al cliente")||document.body.innerText.includes("Aprobar")', 20000); }
catch (e) { const t = await text(op); const i = t.indexOf('Enviar a aprobación'); console.log('DEBUG compose: ' + t.slice(Math.max(0, i - 900), i + 500).replace(/\+?\d[\d ]{7,}/g, '<phone>').replace(/\s+/g, ' ')); throw e; }
chk('submitted message is pending approval, not sent', (await text(op)).includes('Mensaje enviado a aprobación') && (await hasButton(op, 'Aprobar')));
await op.waitFor('document.body.innerText.includes("Lo que recibirá el cliente")', 15000).catch(() => {});
chk('pending message shows what the customer will receive (template preview)', (await text(op)).includes('Lo que recibirá el cliente'));
await clickExact(op, 'Aprobar');
await op.waitFor('document.body.innerText.includes("Confirmar aprobación")', 10000);
await clickExact(op, 'Confirmar aprobación');
await op.waitFor('document.body.innerText.includes("Mensaje aprobado")||document.body.innerText.includes("No se pudo")||document.body.innerText.includes("plantilla")', 20000);
chk('approval bound to the reviewed template preview succeeds', (await text(op)).includes('Mensaje aprobado'));
await clickExact(op, 'Enviar');
await op.waitFor('document.body.innerText.includes("Confirmar envío")', 10000);
await clickExact(op, 'Confirmar envío');
await op.waitFor('document.body.innerText.includes("Los envíos están desactivados en la plataforma")||document.body.innerText.includes("Enviado al proveedor")', 20000);
chk('send refused with the global-disable message (Spanish, no provider text)', (await text(op)).includes('Los envíos están desactivados en la plataforma'));

// --- server-side truth: still APPROVED, no attempt, no provider message id ---
const list = await api(op, 'GET', `/api/messages?customerId=${id}`);
mid = list.body.items[0].messageId;
const detail = await api(op, 'GET', `/api/messages/${mid}`);
chk('message is AI_DRAFT, APPROVED, with zero attempts and no provider id',
  detail.body.origin === 'AI_DRAFT' && detail.body.status === 'APPROVED' && detail.body.attempts.length === 0 && !detail.body.providerMessageId,
  `${detail.body.origin} ${detail.body.status} attempts=${detail.body.attempts.length}`);
chk('delivery history in the workbench lists the message as Aprobado', (await historyText(op)).includes('Aprobado'));

// --- read-only view for the viewer ---
const vw = await open('testviewer', 9342);
await vw.goto(APP + '/'); await vw.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 30000);
chk('viewer opens the same customer', await openCard(vw));
await sleep(2500);
chk('viewer sees no Revisar y enviar / Aprobar / Enviar controls',
  !(await hasButton(vw, 'Revisar y enviar')) && !(await hasButton(vw, 'Aprobar')) && !(await hasButton(vw, 'Enviar')));
chk('viewer still sees the delivery history', (await historyText(vw)).includes('Aprobado'));

// --- clean up: cancel the approved synthetic message through the UI ---
await clickExact(op, 'Cancelar');
await op.waitFor('document.body.innerText.includes("Confirmar cancelación")', 10000);
await clickExact(op, 'Confirmar cancelación');
await op.waitFor('document.body.innerText.includes("Mensaje cancelado")', 20000);
const after = await api(op, 'GET', `/api/messages/${mid}`);
chk('synthetic message cancelled, still zero attempts', after.body.status === 'CANCELLED' && after.body.attempts.length === 0, after.body.status);
} finally {
  // Whatever happened above: cancel the synthetic message if it is still open, and disable the temporary mapping.
  if (!mid) mid = (await api(op, 'GET', `/api/messages?customerId=${id}`)).body?.items?.[0]?.messageId || null;
  if (mid) {
    const left = await api(op, 'GET', `/api/messages/${mid}`);
    if (['PENDING_APPROVAL', 'APPROVED'].includes(left.body.status)) {
      const c = await api(op, 'POST', `/api/messages/${mid}/cancellations`, {}, { 'If-Match': left.etag, 'Idempotency-Key': 'qa140-cleanup-' + RUN });
      console.log(`CLEANUP cancelled the synthetic message: ${c.status}`);
    }
  }
  if (intent) {
    const items = (await api(op, 'GET', '/api/templates')).body.items;
    const cur = items.find(t => t.templateIntent === intent), g = items.find(t => t.templateIntent === 'GENERAL_FOLLOW_UP');
    if (cur.enabled) {
      const off = await api(op, 'PUT', `/api/templates/${intent}`, { providerTemplateName: g.providerTemplateName, language: g.language, parameterNames: [], enabled: false }, { 'If-Match': '"' + cur.version + '"' });
      chk(`temporary ${intent} mapping disabled again`, off.status === 200 && off.body.enabled === false, String(off.status));
    }
  }
}
console.log(`SUMMARY P3-UI PASS=${pass} FAIL=${fail}`);
process.exit(0);
