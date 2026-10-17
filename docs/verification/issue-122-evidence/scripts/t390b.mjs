import { launchChrome, Browser, login, sleep } from './cdp.mjs';
const c = await launchChrome(); const b = await new Browser(c.ws).open(); const p = await (await b.newPage()).init();
await p.viewport(1440, 900); await login(p);
await p.viewport(390, 844); await p.goto('http://localhost:5173/'); await p.waitFor('document.body.innerText.includes("Seguimientos")'); await sleep(900); await p.waitFor('!document.body.innerText.includes("Cargando seguimientos")', 20000); await sleep(500);
console.log(await p.ev(`(()=>{const card=document.querySelector('.follow-up-card');const out=[];for(let n=card;n&&n!==document.documentElement;n=n.parentElement){const s=getComputedStyle(n);out.push(n.tagName+'.'+(n.className||'').toString().slice(0,50)+' w='+Math.round(n.getBoundingClientRect().width)+' disp='+s.display+' minW='+s.minWidth+' ov='+s.overflowX+' gtc='+(s.gridTemplateColumns||'').slice(0,40)+' flex='+s.flex.slice(0,20))}return out.join('\\n')})()`));
console.log(await p.ev(`(()=>{const m=document.querySelector('main');const kids=[...m.querySelectorAll('*')].filter(e=>e.getBoundingClientRect().width>400).slice(0,6).map(e=>e.tagName+'.'+(e.className||'').toString().slice(0,40));return kids.join(' | ')})()`));
c.proc.kill(); process.exit(0);
