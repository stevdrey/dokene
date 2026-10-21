// Minimal WebDriver BiDi harness for Firefox (no dependencies). Fresh profile; headless.
import { spawn } from 'node:child_process';
import { writeFileSync, rmSync, mkdirSync } from 'node:fs';
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export async function launchFirefox({ port = 9222 } = {}) {
  const dir = `/home/stevdrey/qa122/ff-profile-${port}`; rmSync(dir, { recursive: true, force: true }); mkdirSync(dir, { recursive: true });
  const proc = spawn('firefox', ['-headless', '-no-remote', '-profile', dir, `--remote-debugging-port=${port}`, 'about:blank'], { stdio: 'ignore' });
  for (let i = 0; i < 80; i++) { try { const r = await fetch(`http://127.0.0.1:${port}/json/version`); if (r.ok) break; } catch {} await sleep(250); }
  const ws = new WebSocket(`ws://127.0.0.1:${port}/session`); await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const c = { proc, ws, id: 0, pending: new Map(), events: [] };
  ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id && c.pending.has(d.id)) { const { res, rej } = c.pending.get(d.id); c.pending.delete(d.id); d.type === 'error' ? rej(new Error(d.error + ': ' + d.message)) : res(d.result); } else if (d.method) c.events.push(d); };
  c.send = (method, params = {}) => { const id = ++c.id; ws.send(JSON.stringify({ id, method, params })); return new Promise((res, rej) => c.pending.set(id, { res, rej })); };
  const s = await c.send('session.new', { capabilities: {} }); c.version = s.capabilities?.browserVersion;
  const tree = await c.send('browsingContext.getTree', {}); c.ctx = tree.contexts[0].context; return c;
}
export class FF {
  constructor(c) { this.c = c; this.ctx = c.ctx; }
  async ev(expr) { const r = await this.c.send('script.evaluate', { expression: expr, target: { context: this.ctx }, awaitPromise: true, resultOwnership: 'none', serializationOptions: { maxObjectDepth: 6 } }); if (r.type === 'exception') throw new Error('eval: ' + (r.exceptionDetails?.text || '') + ' ' + JSON.stringify(r.exceptionDetails?.exception?.value || '')); return unwrap(r.result); }
  async goto(url) { await this.c.send('browsingContext.navigate', { context: this.ctx, url, wait: 'complete' }).catch(() => {}); await sleep(600); }
  async waitFor(expr, ms = 10000) { const t = Date.now(); while (Date.now() - t < ms) { try { if (await this.ev('!!(' + expr + ')')) return true; } catch {} await sleep(200); } throw new Error('timeout waiting: ' + expr); }
  async viewport(w, h, dpr = null) { await this.c.send('browsingContext.setViewport', { context: this.ctx, viewport: { width: w, height: h }, devicePixelRatio: dpr }); await sleep(500); }
  async shot(path) { const r = await this.c.send('browsingContext.captureScreenshot', { context: this.ctx }); writeFileSync(path, Buffer.from(r.data, 'base64')); }
  async mouse(x, y) { await this.c.send('input.performActions', { context: this.ctx, actions: [{ type: 'pointer', id: 'm', parameters: { pointerType: 'mouse' }, actions: [{ type: 'pointerMove', x: Math.round(x), y: Math.round(y) }, { type: 'pointerDown', button: 0 }, { type: 'pointerUp', button: 0 }] }] }); await sleep(250); }
  async clickText(text, tag = 'button') { const pos = await this.ev(`(()=>{const norm=x=>x.textContent.replace(/\\s+/g,' ');const all=[...document.querySelectorAll(${JSON.stringify(tag)})].filter(x=>(x.offsetParent!==null||getComputedStyle(x).position==='fixed')&&norm(x).includes(${JSON.stringify(text)}));const e=all.filter(x=>![...x.children].some(c=>norm(c).includes(${JSON.stringify(text)})))[0]||all[0];if(!e)return null;e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`); if (!pos) throw new Error('no ' + tag + ' with text ' + text); await this.mouse(pos.x, pos.y); }
  async key(k, { shift = false } = {}) { const V = { Tab: '', Enter: '', Escape: '', ' ': ' ', Shift: '' }; const v = V[k] ?? k; const acts = []; if (shift) acts.push({ type: 'keyDown', value: V.Shift }); acts.push({ type: 'keyDown', value: v }, { type: 'keyUp', value: v }); if (shift) acts.push({ type: 'keyUp', value: V.Shift }); await this.c.send('input.performActions', { context: this.ctx, actions: [{ type: 'key', id: 'k', actions: acts }] }); await sleep(150); }
  async type(text) { for (const ch of text) await this.key(ch); }
}
function unwrap(n) { if (!n) return undefined; switch (n.type) { case 'undefined': case 'null': return null; case 'string': case 'number': case 'boolean': return n.value; case 'array': return (n.value || []).map(unwrap); case 'object': return Object.fromEntries((n.value || []).map(([k, v]) => [typeof k === 'string' ? k : unwrap(k), unwrap(v)])); default: return n.value; } }
