import { launchChrome, Browser, login, sleep } from './cdp.mjs';
import { MEASURE } from './checks.mjs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
await p.viewport(1440, 900); await login(p);
for (const w of [600, 560, 480, 390, 360, 320]) {
  await p.viewport(w, 900, { mobile: false }); await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(900); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await sleep(400);
  const m = await p.ev(MEASURE); console.log(String(w).padStart(5), 'docScrollW', m.docScrollW, 'vw', m.vw, 'overflow', m.horizontalOverflow, 'beyond', m.beyondViewport.length);
}
c.proc.kill(); process.exit(0);
