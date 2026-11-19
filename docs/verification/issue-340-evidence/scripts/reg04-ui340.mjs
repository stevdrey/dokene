// ABOUTME: Suite #340 REG-04 + REG-05 (#82/#83) in real browsers: high-volume due queue ("Cargar más seguimientos"),
// ABOUTME: long purchase history ("Cargar compras anteriores"), workspace switching mid-list, and page overflow at 768/320 px.
// Fails closed: every check is an ASSERT line, and any failed assertion, exception or missing expectation exits nonzero.
// Rows are identified by the ids the UI received from the API (customer ids, purchase ids), not by display text.
// Usage (normally through reg04-ui-run340.sh, which derives the expectations from the database):
//   EXPECT_QUEUE_TOTAL=… EXPECT_VOLUME=… EXPECT_SUR_IDS=<id,…> EXPECT_PURCHASES=… EXPECT_PURCHASE_IDS_MD5=… \
//   PW_HOME=<dir with playwright-core> QA_PW=<testuser password> node reg04-ui340.mjs chromium|firefox
import { createHash } from 'crypto';
import { launch, login, layoutProbe } from '../../issue-338-evidence/scripts/pw338.mjs';

const name = process.argv[2] ?? 'chromium';
let asserts = 0, failed = 0;
const assert = (c, k, ok, detail) => {
  asserts++; if (!ok) failed++;
  console.log(`ASSERT\t${c}\t${name}\t${k}\t${ok ? 'PASS' : 'FAIL'}\t${detail}`);
};
const need = k => { const v = process.env[k]; if (v === undefined || v === '') throw new Error(`missing expectation ${k}`); return v; };
const PAGE = 50; // the workbench and the purchase list both load 50 rows per page

let b;
try {
  const E = {
    queue: Number(need('EXPECT_QUEUE_TOTAL')), volume: Number(need('EXPECT_VOLUME')),
    sur: need('EXPECT_SUR_IDS').split(',').filter(Boolean).sort(),
    purchases: Number(need('EXPECT_PURCHASES')), purchaseMd5: need('EXPECT_PURCHASE_IDS_MD5'), pw: need('QA_PW'),
  };
  if (!(E.queue > PAGE) || !(E.volume > 0) || !(E.purchases > PAGE)) throw new Error('expectations too small to exercise paging');
  b = await launch(name);
  const ctx = await b.newContext({ viewport: { width: 1280, height: 900 } });
  const p = await ctx.newPage();
  // Every follow-up-queue and purchase-list response the UI receives, per phase.
  let bucket = { queue: [], purchases: [] };
  p.on('response', async r => {
    const u = r.url();
    if (r.status() !== 200 || r.request().method() !== 'GET') return;
    try {
      if (/\/api\/follow-up-queue/.test(u)) bucket.queue.push(...(await r.json()).items.map(i => i.customerId));
      else if (/\/api\/customers\/[^/]+\/purchases(\?|$)/.test(u)) bucket.purchases.push(...(await r.json()).purchases.map(x => x.id));
    } catch { /* a navigation may discard the body; the DOM counts below still decide */ }
  });
  const cards = () => p.$$eval('.follow-up-card .follow-up-card__title', hs => hs.map(h => h.innerText.trim()));
  const workspace = async ws => {
    await p.getByRole('button', { name: 'Seleccionar espacio de trabajo' }).click();
    await p.getByRole('option', { name: new RegExp(ws) }).click();
    await p.waitForTimeout(2000);
  };
  const uniq = a => new Set(a).size;

  await login(p, 'testuser', E.pw);
  await p.waitForTimeout(1500);
  // --- high-volume queue ---
  const first = (await cards()).length;
  assert('REG-04', 'first page holds exactly one page of cards', first === PAGE, `cards=${first}`);
  let clicks = 0;
  for (; clicks < Math.ceil(E.queue / PAGE) + 2; clicks++) {
    const more = p.getByRole('button', { name: /Cargar más seguimientos/ });
    if (!(await more.count())) break;
    const before = (await cards()).length;
    await more.click();
    await p.waitForFunction(n => document.querySelectorAll('.follow-up-card').length > n, before, { timeout: 15000 });
  }
  const all = await cards();
  const norteIds = [...bucket.queue];
  assert('REG-04', 'every due customer is shown once after paging', all.length === E.queue && uniq(norteIds) === E.queue && norteIds.length === E.queue,
    `cards=${all.length} received_ids=${norteIds.length} unique_ids=${uniq(norteIds)} expected=${E.queue} load_more_clicks=${clicks}`);
  const vol = all.filter(n => n.startsWith('QA340 Volumen'));
  assert('REG-04', 'every volume customer is shown once', vol.length === E.volume && uniq(vol) === E.volume, `volume_cards=${vol.length} unique=${uniq(vol)} expected=${E.volume}`);
  assert('REG-04', 'Cargar más seguimientos is gone after the last page', !(await p.getByRole('button', { name: /Cargar más seguimientos/ }).count()), `clicks=${clicks}`);
  // --- workspace switch with the full list loaded ---
  bucket = { queue: [], purchases: [] };
  await workspace('QA Café Sur');
  const surCards = await cards();
  const surIds = [...new Set(bucket.queue)].sort();
  assert('REG-04', 'workspace B shows exactly its own due customers and none of A', JSON.stringify(surIds) === JSON.stringify(E.sur)
    && surCards.length === E.sur.length && !surIds.some(i => norteIds.includes(i)),
    `b_cards=${surCards.length} b_ids=${surIds.length} expected=${E.sur.length} overlap_with_a=${surIds.filter(i => norteIds.includes(i)).length}`);
  bucket = { queue: [], purchases: [] };
  await workspace('QA Café Norte');
  const back = await cards();
  assert('REG-04', 'switching back loads a fresh first page of A', back.length === PAGE && bucket.queue.length === PAGE && bucket.queue.every(i => norteIds.includes(i)),
    `cards=${back.length} received_ids=${bucket.queue.length}`);
  // --- long purchase history ---
  await p.getByRole('link', { name: 'Clientes' }).or(p.getByRole('button', { name: 'Clientes' })).first().click();
  await p.getByPlaceholder('Buscar por nombre...').fill('QA340 Historial Largo');
  bucket = { queue: [], purchases: [] };
  await p.getByRole('button', { name: 'QA340 Historial Largo', exact: true }).first().click();
  const rows = () => p.locator('.customer-purchase__row').count();
  await p.waitForFunction(() => document.querySelectorAll('.customer-purchase__row').length > 0, null, { timeout: 15000 });
  const firstRows = await rows();
  assert('REG-04', 'purchase history first page holds one page of rows', firstRows === PAGE, `rows=${firstRows}`);
  let pc = 0;
  for (; pc < Math.ceil(E.purchases / PAGE) + 2; pc++) {
    const more = p.getByRole('button', { name: /Cargar compras anteriores/ });
    if (!(await more.count())) break;
    const before = await rows();
    await more.click();
    await p.waitForFunction(n => document.querySelectorAll('.customer-purchase__row').length > n, before, { timeout: 15000 });
  }
  const ids = bucket.purchases;
  const md5 = createHash('md5').update([...new Set(ids)].sort().join('\n')).digest('hex');
  const shown = await rows();
  assert('REG-04', 'every purchase row is shown once (by purchase id)', shown === E.purchases && ids.length === E.purchases && uniq(ids) === E.purchases && md5 === E.purchaseMd5,
    `rows=${shown} received_ids=${ids.length} unique_ids=${uniq(ids)} expected=${E.purchases} id_set_matches_db=${md5 === E.purchaseMd5} clicks=${pc}`);
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
      assert('REG-05', `${view}@${w}px no page overflow`, !r.pageOverflowX && r.overflowing.length === 0 && r.clippedControls.length === 0,
        `scrollWidth=${r.docScrollWidth}/${r.vw} overflowing=${r.overflowing.length} clippedControls=${r.clippedControls.length}`);
    }
  }
} catch (e) {
  failed++;
  console.log(`ERROR\t${name}\texception\t${String(e).slice(0, 300)}`);
}
if (b) await b.close();
console.log(`DONE\t${name}\tasserts=${asserts}\tfailed=${failed}`);
process.exit(failed === 0 && asserts >= 12 ? 0 : 1);
