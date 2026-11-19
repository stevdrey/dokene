// ABOUTME: Suite #340 REG-04 + REG-05 (#82/#83) in real browsers: high-volume due queue ("Cargar más seguimientos"),
// ABOUTME: long purchase history ("Cargar compras anteriores"), workspace switching mid-list, and page overflow at 768/320 px.
// Usage: PW_HOME=<dir with playwright-core> QA_PW=<testuser password> node reg04-ui340.mjs [chromium|firefox]
import { launch, login, layoutProbe } from '../../issue-338-evidence/scripts/pw338.mjs';

const name = process.argv[2] ?? 'chromium';
const b = await launch(name);
const out = (c, k, v) => console.log(`${c}\t${name}\t${k}\t${v}`);
const ctx = await b.newContext({ viewport: { width: 1280, height: 900 } });
const p = await ctx.newPage();
const queueNames = () => p.evaluate(() => [...document.querySelectorAll('h3')].map(h => h.innerText.trim()).filter(t => t.startsWith('QA')));
const workspace = async ws => {
  await p.getByRole('button', { name: 'Seleccionar espacio de trabajo' }).click();
  await p.getByRole('option', { name: new RegExp(ws) }).click();
  await p.waitForTimeout(1500);
};
try {
  await login(p, 'testuser', process.env.QA_PW);
  await p.waitForTimeout(1500);
  // --- high-volume queue ---
  let names = await queueNames();
  out('REG-04', 'first_load_cards', names.length);
  let clicks = 0;
  for (; clicks < 10; clicks++) {
    const more = p.getByRole('button', { name: /Cargar más seguimientos/ });
    if (!(await more.count())) break;
    await more.click();
    await p.waitForFunction(n => [...document.querySelectorAll('h3')].filter(h => h.innerText.trim().startsWith('QA')).length > n, names.length, { timeout: 15000 }).catch(() => {});
    names = await queueNames();
  }
  const vol = names.filter(n => n.startsWith('QA340 Volumen'));
  out('REG-04', 'load_more_clicks', clicks);
  out('REG-04', 'cards_after_all_pages', names.length);
  out('REG-04', 'volume_cards_unique', `${new Set(vol).size}/${vol.length}`);
  out('REG-04', 'load_more_gone_at_end', !(await p.getByRole('button', { name: /Cargar más seguimientos/ }).count()));
  const t = await p.innerText('main');
  out('REG-04', 'counters', (t.match(/Pendientes\n(\d+)/) || [])[1] + ' pendientes / ' + (t.match(/Vencidos\n(\d+)/) || [])[1] + ' vencidos');
  // --- workspace switch with a fully loaded list ---
  await workspace('QA Café Sur');
  const sur = await queueNames();
  out('REG-04', 'sur_queue_cards', sur.join('|'));
  out('REG-04', 'sur_has_no_norte_card', !sur.some(n => n.startsWith('QA340 Volumen') || n.includes('Navegador')));
  await workspace('QA Café Norte');
  const back = await queueNames();
  out('REG-04', 'norte_after_switch_back_cards', back.length);
  out('REG-04', 'norte_back_is_fresh_first_page', back.length <= 50);
  // --- long purchase history ---
  await p.getByRole('link', { name: 'Clientes' }).or(p.getByRole('button', { name: 'Clientes' })).first().click();
  await p.getByPlaceholder('Buscar por nombre...').fill('QA340 Historial Largo');
  await p.getByRole('button', { name: 'QA340 Historial Largo', exact: true }).first().click();
  await p.getByText(/Historial de compras \(\d+\)/).waitFor({ timeout: 15000 });
  await p.getByText(/Compra larga \d{3}/).first().waitFor({ timeout: 15000 });
  const count = async () => Number((await p.getByText(/Historial de compras \(\d+\)/).innerText()).match(/\((\d+)\)/)[1]);
  let h = await count(); out('REG-04', 'purchase_history_first_page', h);
  let pc = 0;
  for (; pc < 6; pc++) {
    const more = p.getByRole('button', { name: /Cargar compras anteriores/ });
    if (!(await more.count())) break;
    const before = h; await more.click();
    await p.waitForFunction(n => { const m = document.body.innerText.match(/Historial de compras \((\d+)\)/); return m && Number(m[1]) > n; }, before, { timeout: 15000 }).catch(() => {});
    h = await count();
  }
  out('REG-04', 'purchase_history_after_all_pages', `${h} (clicks=${pc})`);
  const descs = await p.evaluate(() => (document.body.innerText.match(/Compra larga \d{3}/g) || []));
  out('REG-04', 'purchase_rows_unique', `${new Set(descs).size}/${descs.length}`);
  // --- #82 / #83 responsive overflow ---
  for (const [w, hgt] of [[768, 1024], [320, 568]]) {
    await p.setViewportSize({ width: w, height: hgt });
    for (const view of ['Clientes', 'Seguimientos']) {
      await p.goto(process.env.APP_URL ?? 'http://localhost:5173');
      await p.waitForTimeout(1200);
      if (view === 'Clientes') {
        const nav = p.getByRole('link', { name: 'Clientes' }).or(p.getByRole('button', { name: 'Clientes' }));
        if (await nav.count()) { await nav.first().click(); await p.waitForTimeout(1200); }
      }
      const r = await layoutProbe(p);
      out('REG-05', `${view}@${w}px overflow`, `pageOverflowX=${r.pageOverflowX} scrollWidth=${r.docScrollWidth}/${r.vw} overflowing=${r.overflowing.length} clippedControls=${r.clippedControls.length}`);
    }
  }
} catch (e) { out('ERROR', 'exception', String(e).slice(0, 300)); }
await b.close();
