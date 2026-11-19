// ABOUTME: Suite #338 UX-03 supporting audit (NOT a screen-reader session): names, landmarks, live regions, WCAG AA
// ABOUTME: text contrast and target size on the Phase 3 screens (review, approval confirmation, history, integration).
//   QA_PW=... node ux03b-338.mjs
import { launch, login, save } from './pw338.mjs';

const browser = await launch('chromium');
const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'es-CL' })).newPage();
await login(page, 'testuser', process.env.QA_PW);

const audit = () => page.evaluate(() => {
  const lum = c => { const m = c.match(/[\d.]+/g).map(Number); const [r, g, b] = m.slice(0, 3).map(v => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
  const alpha = c => { const m = c.match(/[\d.]+/g); return m && m.length > 3 ? Number(m[3]) : 1; };
  const bg = el => { for (let e = el; e; e = e.parentElement) { const c = getComputedStyle(e).backgroundColor; if (alpha(c) > 0.5) return c; } return 'rgb(255, 255, 255)'; };
  const vis = el => { const s = getComputedStyle(el); const r = el.getBoundingClientRect(); return s.visibility !== 'hidden' && s.display !== 'none' && r.width > 2 && r.height > 2; };
  const nameOf = el => (el.getAttribute('aria-label') || (el.getAttribute('aria-labelledby') && document.getElementById(el.getAttribute('aria-labelledby'))?.textContent) || (el.labels && [...el.labels].map(l => l.textContent).join(' ')) || el.getAttribute('title') || [...el.childNodes].filter(n => !(n.nodeType === 1 && (n.getAttribute('aria-hidden') === 'true'))).map(n => n.textContent).join('') || el.getAttribute('placeholder') || '').trim();
  const controls = [...document.querySelectorAll('button, a[href], input:not([type=hidden]), select, textarea, [role=button]')].filter(vis);
  const unnamed = controls.filter(el => !nameOf(el)).map(el => el.outerHTML.slice(0, 80));
  const small = controls.filter(el => { const r = el.getBoundingClientRect(); return (r.width < 24 || r.height < 24) && !el.closest('p,li'); }).map(el => `${nameOf(el).slice(0, 30)} ${Math.round(el.getBoundingClientRect().width)}x${Math.round(el.getBoundingClientRect().height)}`);
  const lowContrast = [];
  let textNodes = 0;
  for (const el of document.querySelectorAll('body *')) {
    if (!vis(el) || el.closest('.material-symbols-outlined,[aria-hidden=true]')) continue;
    const own = [...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim());
    if (!own) continue;
    textNodes++;
    const s = getComputedStyle(el); if (alpha(s.color) < 0.5) continue;
    const L1 = lum(s.color), L2 = lum(bg(el)); const ratio = (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
    const size = parseFloat(s.fontSize), bold = Number(s.fontWeight) >= 700;
    const need = size >= 24 || (bold && size >= 18.66) ? 3 : 4.5;
    if (ratio < need && !el.closest('button[disabled]')) lowContrast.push(`${el.textContent.trim().slice(0, 30)} ${ratio.toFixed(2)}<${need}`);
  }
  return { lang: document.documentElement.lang, landmarks: [...document.querySelectorAll('main,nav,aside,header,[role=region][aria-label],section[aria-label],section[aria-labelledby]')].length,
    controls: controls.length, unnamed, small: [...new Set(small)].slice(0, 10), textNodes, lowContrast: [...new Set(lowContrast)].slice(0, 10),
    alerts: document.querySelectorAll('[role=alert]').length, statuses: document.querySelectorAll('[role=status],[aria-live]').length,
    headings: [...document.querySelectorAll('h1,h2,h3,h4')].filter(vis).map(h => h.tagName[1]).join('') };
});
const out = {};
const open = async name => {
  await page.getByRole('heading', { name, level: 3 }).click();
  const row = page.getByRole('list', { name: 'Mensajes del cliente' }).getByRole('button').first();
  await row.waitFor(); if ((await row.getAttribute('aria-expanded')) !== 'true') await row.click();
  await page.getByRole('button', { name: 'Abrir en revisión' }).first().click();
  await page.locator('section.message-review').filter({ hasText: /Revisión \d+/ }).first().waitFor();
};
await open('QA338 Vista Pendiente'); out['review-pending'] = await audit();
await page.getByRole('button', { name: 'Aprobar', exact: true }).click(); out['approve-confirm'] = await audit();
await page.getByRole('button', { name: 'Cerrar', exact: true }).click();
await open('QA338 Vista Aprobada');
await page.getByRole('button', { name: 'Enviar', exact: true }).click(); await page.getByRole('button', { name: 'Confirmar envío' }).click();
await page.getByRole('alert').first().waitFor(); out['send-refused'] = await audit();
await page.getByRole('button', { name: 'Cerrar', exact: true }).click();
await open('QA338 Estado Fallido'); out['history-failed'] = await audit();
await page.getByRole('button', { name: 'Configuración', exact: true }).click(); await page.getByRole('heading', { name: 'Integración de WhatsApp' }).waitFor(); out['integration'] = await audit();
for (const [k, v] of Object.entries(out)) console.log(`${k.padEnd(16)} lang=${v.lang} landmarks=${v.landmarks} controls=${v.controls} unnamed=${v.unnamed.length} small<24px=${v.small.length} text=${v.textNodes} lowContrast=${v.lowContrast.length} alerts=${v.alerts} live=${v.statuses} headings=${v.headings}${v.lowContrast.length ? ' :: ' + v.lowContrast.join(' | ') : ''}${v.small.length ? ' :: small ' + v.small.join(' | ') : ''}`);
save('ux03b-audit.json', out);
await browser.close();
