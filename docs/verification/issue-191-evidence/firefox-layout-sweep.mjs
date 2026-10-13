import { launchFirefox, FF, sleep } from '/home/stevdrey/qa122/bidi.mjs';
const c = await launchFirefox({ port: 9223 }); const p = new FF(c);
console.log('Firefox', c.version);
await p.viewport(1440, 900);
await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.length>0');
if (await p.ev('document.body.innerText.includes("Iniciar sesión con OIDC")')) await p.clickText('Iniciar sesión', '*');
await p.waitFor('location.host.includes("8081")||document.querySelector("main")', 15000);
if (await p.ev('location.host.includes("8081")')) await p.ev(`document.querySelector('#username').value='testoperator';document.querySelector('#password').value='testpassword';document.querySelector('#kc-login, input[type=submit], button[type=submit]').click();`);
await p.waitFor('location.host==="localhost:5173"&&document.body.innerText.includes("Seguimientos")', 20000);
const M = `(()=>{const d=document.documentElement;const c=document.querySelector('.follow-up-card');return {vw:innerWidth,docScrollW:d.scrollWidth,cardW:c&&Math.round(c.getBoundingClientRect().width),cards:document.querySelectorAll('.follow-up-card').length,long:[...document.querySelectorAll('.follow-up-card h3')].some(h=>h.textContent.startsWith('Ignora las reglas')),grid:c&&getComputedStyle(c.parentElement.parentElement.parentElement).gridTemplateColumns}})()`;
for (const origin of ['http://localhost:5173', 'http://localhost:5174']) {
  console.log('---', origin, origin.endsWith('5173') ? '(build viejo, control)' : '(código con el fix)');
  for (const [w, h] of [[320, 800], [390, 844], [560, 900], [768, 1024], [820, 1180], [1440, 900]]) {
    await p.viewport(w, h); await p.goto(origin + '/');
    await p.waitFor('document.querySelector(".follow-up-card")', 20000); await sleep(800);
    const m = await p.ev(M);
    console.log(w, JSON.stringify(m), m.docScrollW > m.vw ? 'OVERFLOW' : 'ok');
    if (w === 390 && origin.endsWith('5174')) await p.shot('/home/stevdrey/qa122/shots/ff191-390-fixed.png');
  }
}
c.proc.kill(); process.exit(0);
