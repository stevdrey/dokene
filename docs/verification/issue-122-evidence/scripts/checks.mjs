// In-page layout/accessibility measurements (injected via Runtime.evaluate).
export const MEASURE = `(()=>{
const vis=e=>{const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0'};
const sel=e=>{let s=e.tagName.toLowerCase();if(e.id)s+='#'+e.id;const c=(e.getAttribute('class')||'').split(/\\s+/).filter(Boolean).slice(0,2).join('.');if(c)s+='.'+c;return s};
const out={vw:innerWidth,vh:innerHeight,docScrollW:document.documentElement.scrollWidth,bodyScrollW:document.body.scrollWidth};
out.horizontalOverflow=document.documentElement.scrollWidth>innerWidth+1;
// elements wider than the viewport or clipped
out.beyondViewport=[...document.querySelectorAll('body *')].filter(e=>vis(e)).filter(e=>{const r=e.getBoundingClientRect();return r.right>innerWidth+1&&getComputedStyle(e).position!=='fixed'}).slice(0,8).map(e=>sel(e)+' right='+Math.round(e.getBoundingClientRect().right));
out.clippedText=[...document.querySelectorAll('body *')].filter(e=>vis(e)&&e.children.length===0&&e.textContent.trim()).filter(e=>{const s=getComputedStyle(e);return (s.overflow==='hidden'||s.textOverflow==='ellipsis')&&e.scrollWidth>e.clientWidth+1}).slice(0,10).map(e=>sel(e)+' "'+e.textContent.trim().slice(0,30)+'" sw='+e.scrollWidth+' cw='+e.clientWidth);
// interactive targets
const inter=[...document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,[role=button],[role=tab],[role=link],[tabindex]:not([tabindex="-1"])')].filter(vis);
out.interactiveCount=inter.length;
out.smallTargets=inter.filter(e=>{const r=e.getBoundingClientRect();return r.width<24||r.height<24}).slice(0,12).map(e=>sel(e)+' '+Math.round(e.getBoundingClientRect().width)+'x'+Math.round(e.getBoundingClientRect().height)+' "'+(e.getAttribute('aria-label')||e.textContent||'').trim().slice(0,24)+'"');
out.targetsUnder44=inter.filter(e=>{const r=e.getBoundingClientRect();return r.width<44||r.height<44}).length;
out.offscreenInteractive=inter.filter(e=>{const r=e.getBoundingClientRect();return r.right<0||r.left>innerWidth||r.bottom<0&&false}).length;
// accessible names
out.unnamed=inter.filter(e=>{const n=(e.getAttribute('aria-label')||e.getAttribute('aria-labelledby')||e.textContent||e.getAttribute('title')||e.getAttribute('placeholder')||'').trim();const lab=e.labels&&e.labels.length;return !n&&!lab}).slice(0,8).map(e=>sel(e));
// contrast
const parse=c=>{const m=c.match(/rgba?\\(([^)]+)\\)/);if(!m)return null;const p=m[1].split(/[ ,\\/]+/).filter(Boolean).map(Number);return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1}};
const lum=({r,g,b})=>{const f=v=>{v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4)};return 0.2126*f(r)+0.7152*f(g)+0.0722*f(b)};
const blend=(f,b)=>({r:f.r*f.a+b.r*(1-f.a),g:f.g*f.a+b.g*(1-f.a),b:f.b*f.a+b.b*(1-f.a),a:1});
const bgOf=e=>{let stack=[];for(let n=e;n;n=n.parentElement){const s=getComputedStyle(n);if(s.backgroundImage&&s.backgroundImage!=='none')return null;const c=parse(s.backgroundColor);if(c&&c.a>0){stack.push(c);if(c.a>=1)break}}let base={r:255,g:255,b:255,a:1};for(const c of stack.reverse())base=blend(c,base);return base};
const fails=[];let checked=0;
for(const e of document.querySelectorAll('body *')){if(!vis(e))continue;const own=[...e.childNodes].filter(n=>n.nodeType===3&&n.textContent.trim()).length;if(!own)continue;const s=getComputedStyle(e);const fg0=parse(s.color);const bg=bgOf(e);if(!fg0||!bg)continue;const fg=blend(fg0,bg);const L1=lum(fg),L2=lum(bg);const ratio=(Math.max(L1,L2)+0.05)/(Math.min(L1,L2)+0.05);const size=parseFloat(s.fontSize);const bold=parseInt(s.fontWeight)>=700;const large=size>=24||(size>=18.66&&bold);const need=large?3:4.5;checked++;if(ratio<need)fails.push(sel(e)+' "'+e.textContent.trim().slice(0,28)+'" '+ratio.toFixed(2)+' < '+need)}
out.contrastChecked=checked;out.contrastFails=fails.slice(0,12);out.contrastFailCount=fails.length;
out.csp=window.__csp||[];
return out})()`;
