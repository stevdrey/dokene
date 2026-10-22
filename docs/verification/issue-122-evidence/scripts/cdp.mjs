// Minimal Chrome DevTools Protocol harness for QA suite #122 (no dependencies; Node >= 22). Fresh headless Chrome profile per run.
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, rmSync } from 'node:fs';
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export async function launchChrome({ port = 9333, headless = true } = {}) {
  const dir = `/home/stevdrey/qa122/chrome-profile-${port}`; rmSync(dir, { recursive: true, force: true }); mkdirSync(dir, { recursive: true });
  const args = [`--remote-debugging-port=${port}`, `--user-data-dir=${dir}`, '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--window-size=1440,900', '--force-device-scale-factor=1', '--lang=es-419', 'about:blank'];
  if (headless) args.unshift('--headless=new');
  const proc = spawn('google-chrome', args, { stdio: 'ignore' });
  for (let i = 0; i < 50; i++) { try { const v = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json(); return { proc, ws: v.webSocketDebuggerUrl, version: v.Browser }; } catch { await sleep(200); } }
  throw new Error('chrome did not start');
}
export class Browser {
  constructor(wsUrl) { this.ws = new WebSocket(wsUrl); this.id = 0; this.pending = new Map(); this.listeners = []; }
  async open() { await new Promise((r, j) => { this.ws.onopen = r; this.ws.onerror = j; });
    this.ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id && this.pending.has(d.id)) { const { res, rej } = this.pending.get(d.id); this.pending.delete(d.id); d.error ? rej(new Error(d.error.message)) : res(d.result); } else if (d.method) this.listeners.forEach((l) => l(d)); }; return this; }
  send(method, params = {}, sessionId) { const id = ++this.id; this.ws.send(JSON.stringify({ id, method, params, sessionId })); return new Promise((res, rej) => this.pending.set(id, { res, rej })); }
  async newPage() { const { targetId } = await this.send('Target.createTarget', { url: 'about:blank' }); const { sessionId } = await this.send('Target.attachToTarget', { targetId, flatten: true }); return new Page(this, sessionId, targetId); }
}
export class Page {
  constructor(b, sid, tid) { this.b = b; this.sid = sid; this.tid = tid; this.events = []; b.listeners.push((d) => { if (d.sessionId === sid) this.events.push(d); }); }
  send(m, p) { return this.b.send(m, p, this.sid); }
  async init() { for (const d of ['Page', 'Runtime', 'Network', 'Log', 'Accessibility', 'DOM']) await this.send(`${d}.enable`).catch(() => {});
    await this.send('Page.addScriptToEvaluateOnNewDocument', { source: "window.__csp=[];document.addEventListener('securitypolicyviolation',e=>window.__csp.push(e.violatedDirective+' '+e.blockedURI));" }); return this; }
  async ev(expr) { const r = await this.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }
  async goto(url) { await this.send('Page.navigate', { url }); await sleep(800); await this.waitFor('document.readyState==="complete"', 15000); }
  async waitFor(expr, ms = 10000) { const t = Date.now(); while (Date.now() - t < ms) { try { if (await this.ev('!!(' + expr + ')')) return true; } catch {} await sleep(150); } throw new Error('timeout waiting: ' + expr); }
  async viewport(w, h, { mobile = false, dsf = 1 } = {}) { await this.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: dsf, mobile }); await sleep(400); }
  async shot(path) { await this.send('Page.bringToFront').catch(() => {}); const r = await Promise.race([this.send('Page.captureScreenshot', { format: 'png' }), sleep(15000).then(() => { throw new Error('screenshot timeout'); })]); writeFileSync(path, Buffer.from(r.data, 'base64')); }
  async key(key, { shift = false, code } = {}) { const map = { Tab: 9, Enter: 13, Escape: 27, ' ': 32, ArrowDown: 40, ArrowUp: 38, Backspace: 8 }; const vk = map[key] ?? key.charCodeAt(0); const m = shift ? 8 : 0;
    const base = { key, code: code || (key.length === 1 ? 'Key' + key.toUpperCase() : key), windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers: m };
    await this.send('Input.dispatchKeyEvent', { type: 'keyDown', ...base, text: key === 'Enter' ? '\r' : key === ' ' ? ' ' : undefined }); await this.send('Input.dispatchKeyEvent', { type: 'keyUp', ...base }); await sleep(120); }
  async type(text) { await this.send('Input.insertText', { text }); await sleep(100); }
  async click(sel) { const pos = await this.ev(`(()=>{const e=document.querySelector(${JSON.stringify(sel)});if(!e)return null;e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`); if (!pos) throw new Error('no element ' + sel); await this.mouse(pos.x, pos.y); }
  async mouse(x, y) { await this.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y }); await this.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 }); await this.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 }); await sleep(250); }
  async clickText(text, tag = 'button') { const pos = await this.ev(`(()=>{const norm=x=>x.textContent.replace(/\\s+/g,' ');const all=[...document.querySelectorAll(${JSON.stringify(tag)})].filter(x=>(x.offsetParent!==null||getComputedStyle(x).position==='fixed')&&norm(x).includes(${JSON.stringify(text)}));const e=all.filter(x=>![...x.children].some(c=>norm(c).includes(${JSON.stringify(text)})))[0]||all[0];if(!e)return null;e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`); if (!pos) throw new Error('no ' + tag + ' with text ' + text); await this.mouse(pos.x, pos.y); }
  async ax() { const { nodes } = await this.send('Accessibility.getFullAXTree'); return nodes; }
}
export async function login(page, user = 'testoperator') {
  await page.goto('http://localhost:5173/'); await page.waitFor('document.body.innerText.length>0');
  if (await page.ev('document.body.innerText.includes("Iniciar sesión con OIDC")')) { await page.clickText('Iniciar sesión', '*'); }
  await page.waitFor('location.host.includes("8081")||document.querySelector("main")', 15000);
  if (await page.ev('location.host.includes("8081")')) { await page.ev(`document.querySelector('#username').value=${JSON.stringify(user)};document.querySelector('#password').value=${JSON.stringify(process.env.DOKENE_TEST_USER_PASSWORD || '')};document.querySelector('#kc-login, input[type=submit], button[type=submit]').click();`); }
  await page.waitFor('location.host==="localhost:5173"&&document.querySelector("main")&&document.body.innerText.includes("Seguimientos")', 20000);
}
export const FOCUS = `(()=>{const e=document.activeElement;if(!e||e===document.body)return {tag:'BODY'};const s=getComputedStyle(e);const r=e.getBoundingClientRect();const name=(e.getAttribute('aria-label')||e.getAttribute('title')||(e.labels&&e.labels[0]&&e.labels[0].textContent)||e.textContent||e.getAttribute('placeholder')||'').replace(/\\s+/g,' ').trim().slice(0,48);
const dlg=e.closest('[role=dialog],[aria-modal=true],dialog');return {tag:e.tagName.toLowerCase(),role:e.getAttribute('role')||'',name,focusVisible:e.matches(':focus-visible'),outline:s.outlineStyle+' '+s.outlineWidth,shadow:s.boxShadow==='none'?'':'shadow',inViewport:r.top>=0&&r.bottom<=innerHeight,inDialog:!!dlg}})()`;
export async function focusTrail(page, keys, label) { const out = []; for (const k of keys) { if (k === 'Tab' || k === 'Shift+Tab') await page.key('Tab', { shift: k === 'Shift+Tab' }); else await page.key(k); out.push(await page.ev(FOCUS)); } return out; }
