import { launchChrome, Browser, login, sleep } from './cdp.mjs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
await p.viewport(1440, 900); await login(p); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000);
await p.ev(`(()=>{const i=document.querySelector('input[placeholder*="Buscar"]');const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,'Dormant');i.dispatchEvent(new Event('input',{bubbles:true}))})()`); await sleep(1100); await p.clickText('Dormant Té QA', '*'); await sleep(900);
console.log(JSON.stringify(await p.ev(`[...document.querySelectorAll('a[href],button,input:not([type=hidden]),[role=button],select,textarea')].filter(e=>e.offsetParent!==null).map(e=>{const r=e.getBoundingClientRect();return [Math.round(r.width)+'x'+Math.round(r.height),(e.getAttribute('aria-label')||e.textContent||e.placeholder||'').trim().replace(/\\s+/g,' ').slice(0,28)]}).filter(([s])=>{const [w,h]=s.split('x').map(Number);return w<44||h<44})`)));
c.proc.kill(); process.exit(0);
