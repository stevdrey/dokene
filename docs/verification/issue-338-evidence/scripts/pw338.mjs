// ABOUTME: Suite #338 browser harness: launches real Chromium (Playwright build) or the system Firefox over WebDriver
// ABOUTME: BiDi, signs in through the real Keycloak form, and offers layout/overflow/reach probes. Never logs secrets.
import { createRequire } from 'module';
import fs from 'fs';
const require = createRequire(process.env.PW_HOME ?? '/home/stevdrey/Projects/Java/faro/apps/web/');
const pw = require('playwright-core');
export const APP = process.env.APP_URL ?? 'http://localhost:5173';
export const OUT = process.env.QA338_OUT ?? `${process.env.HOME}/qa338`;

export async function launch(name) {
  if (name === 'firefox') return pw.firefox.launch({ headless: true, channel: 'moz-firefox', executablePath: '/usr/bin/firefox' });
  return pw.chromium.launch({ headless: true });
}

/** Signs in through the real login button and the Keycloak form. The password comes from the caller's environment. */
export async function login(page, user, password) {
  await page.goto(APP);
  await page.getByRole('button', { name: /iniciar sesi/i }).first().click();
  await page.waitForSelector('#username', { timeout: 20000 });
  await page.fill('#username', user);
  await page.fill('#password', password);
  await Promise.all([page.waitForURL(u => u.toString().startsWith(APP), { timeout: 30000 }), page.click('#kc-login')]);
  await page.getByRole('heading', { name: 'Seguimientos', level: 1 }).waitFor({ timeout: 20000 });
}

/** Layout probe: page-level horizontal overflow, elements past the viewport edge, clipped controls and clipped text. */
export async function layoutProbe(page) {
  return page.evaluate(() => {
    const vw = document.documentElement.clientWidth;
    const out = { vw, docScrollWidth: document.documentElement.scrollWidth, pageOverflowX: document.documentElement.scrollWidth > vw + 1, overflowing: [], clippedControls: [], clippedText: [] };
    const vis = el => { const s = getComputedStyle(el); const r = el.getBoundingClientRect(); return s.visibility !== 'hidden' && s.display !== 'none' && r.width > 0 && r.height > 0; };
    const name = el => (el.getAttribute('aria-label') || el.innerText || el.value || el.tagName).trim().replace(/\s+/g, ' ').slice(0, 60);
    for (const el of document.querySelectorAll('body *')) {
      if (!vis(el) || el.closest('.material-symbols-outlined')) continue;
      const r = el.getBoundingClientRect();
      if (r.right > vw + 1 || r.left < -1) {
        // An element inside an intended horizontal scroller is reachable; one inside an overflow:hidden box is clipped.
        let a = el.parentElement, kind = null;
        while (a && a !== document.body) {
          const s = getComputedStyle(a);
          if (/(auto|scroll)/.test(s.overflowX) && a.scrollWidth > a.clientWidth) { kind = 'scroll'; break; }
          if (/(hidden|clip)/.test(s.overflowX)) { kind = 'clip'; break; }
          a = a.parentElement;
        }
        if (!kind) out.overflowing.push(`${el.tagName.toLowerCase()}.${String(el.className).slice(0, 30)} right=${Math.round(r.right)}`);
        if (kind === 'clip' && el.matches('button,a,input,select,textarea,[role=button],[role=alert],[role=status]')) out.clippedControls.push(name(el));
      }
      const s = getComputedStyle(el);
      // Visually hidden labels (1px clip boxes for screen readers) are intended, not clipped text.
      if (r.width <= 2 || r.height <= 2) continue;
      if (el.matches('button,[role=alert],[role=status],label,h1,h2,h3,p,li') && el.scrollWidth > el.clientWidth + 1 && /(hidden|clip)/.test(s.overflowX) && s.textOverflow !== 'ellipsis')
        out.clippedText.push(name(el));
    }
    // A word split across two lines inside a control or badge (for example "Actualiz" / "ar").
    out.brokenWords = [];
    for (const el of document.querySelectorAll('button, [role=button], .badge, [class*=badge]')) {
      if (!vis(el)) continue;
      const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        if (n.parentElement.closest('.material-symbols-outlined')) continue;
        const re = /[^\s\-‐–—]+/g; let m;
        while ((m = re.exec(n.data))) {
          const range = document.createRange(); range.setStart(n, m.index); range.setEnd(n, m.index + m[0].length);
          const tops = new Set([...range.getClientRects()].filter(q => q.width > 0).map(q => Math.round(q.top)));
          if (tops.size > 1) out.brokenWords.push(`${m[0]} in "${name(el)}"`);
        }
      }
    }
    out.brokenWords = [...new Set(out.brokenWords)].slice(0, 15);
    out.overflowing = [...new Set(out.overflowing)].slice(0, 15);
    out.clippedControls = [...new Set(out.clippedControls)].slice(0, 15);
    out.clippedText = [...new Set(out.clippedText)].slice(0, 15);
    return out;
  });
}

/** Every enabled visible control: once scrolled into view, is it inside the viewport and not covered by another element? */
export async function reachProbe(page) {
  const handles = await page.$$('button:not([disabled]), a[href], input:not([type=hidden]):not([disabled]), select, textarea, [role=button]');
  const unreachable = [];
  let n = 0;
  for (const h of handles) {
    const info = await h.evaluate(el => {
      const s = getComputedStyle(el); const r = el.getBoundingClientRect();
      if (s.visibility === 'hidden' || s.display === 'none' || r.width === 0 || r.height === 0) return null;
      if (el.closest('[aria-hidden=true],[inert]')) return null;
      el.scrollIntoView({ block: 'center', inline: 'center' });
      const r2 = el.getBoundingClientRect();
      const x = Math.min(Math.max(r2.left + r2.width / 2, 0), innerWidth - 1), y = Math.min(Math.max(r2.top + r2.height / 2, 0), innerHeight - 1);
      const hit = document.elementFromPoint(x, y);
      // A control taller or wider than the viewport counts as reachable when its centre is on screen and hit-testable.
      const fits = r2.width <= innerWidth + 1 && r2.height <= innerHeight + 1;
      const inView = fits ? (r2.left >= -1 && r2.right <= innerWidth + 1 && r2.top >= -1 && r2.bottom <= innerHeight + 1) : (x > 0 && y > 0);
      const ok = inView && hit && (hit === el || el.contains(hit) || hit.contains(el));
      return { ok, name: (el.getAttribute('aria-label') || el.innerText || el.placeholder || el.tagName).trim().replace(/\s+/g, ' ').slice(0, 50), size: `${Math.round(r2.width)}x${Math.round(r2.height)}`, cover: hit && !ok ? `${hit.tagName.toLowerCase()}.${String(hit.className).slice(0, 25)}` : '' };
    });
    if (!info) continue;
    n++;
    if (!info.ok) unreachable.push(`${info.name} (${info.size}${info.cover ? ', covered by ' + info.cover : ''})`);
  }
  await page.evaluate(() => window.scrollTo(0, 0));
  return { controls: n, unreachable };
}

export async function text(page) { return (await page.innerText('body')).replace(/\n{3,}/g, '\n\n'); }
export function save(file, data) {
  fs.mkdirSync(`${OUT}/out`, { recursive: true });
  fs.writeFileSync(`${OUT}/out/${file}`, typeof data === 'string' ? data : JSON.stringify(data, null, 2));
}
export function shot(page, file) { fs.mkdirSync(`${OUT}/shots`, { recursive: true }); return page.screenshot({ path: `${OUT}/shots/${file}`, fullPage: true }); }
