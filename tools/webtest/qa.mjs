// Headless checks of the web app (docs/app) against tools/webtest/srv.py. Every non-local request is mocked or
// blocked, so no real service is hit. Run: python3 tools/webtest/srv.py & node tools/webtest/qa.mjs
// CHROME_PATH: optional Chromium binary (else Playwright's own).
import { chromium } from 'playwright';
const B='http://127.0.0.1:8781/index.html';
const b = await chromium.launch(process.env.CHROME_PATH ? {executablePath: process.env.CHROME_PATH} : {});
const now=Date.now();
const team=(loc,nick,ab,ha,score)=>({homeAway:ha,score,team:{displayName:loc+' '+nick,shortDisplayName:nick,name:nick,location:loc,abbreviation:ab,logo:''}});
const sb={week:{number:6},events:[
 {id:'1',date:new Date(now-3600e3).toISOString(),status:{type:{state:'in',shortDetail:'Q3 5:12'}},competitions:[{competitors:[team('Dallas','Cowboys','DAL','home','17'),team('New York','Giants','NYG','away','10')],broadcasts:[{names:['ESPN']}]}]},
 {id:'2',date:new Date(now+86400e3).toISOString(),status:{type:{state:'pre',shortDetail:''}},competitions:[{competitors:[team('Kansas City','Chiefs','KC','home','0'),team('Buffalo','Bills','BUF','away','0')],broadcasts:[{names:['FS1']}]}]}]};
const fails=[]; const log=(...a)=>console.log(...a);
async function mk(vp){
  const ctx=await b.newContext({viewport:vp}); const p=await ctx.newPage();
  const errs=[], blocked=new Set(), espn=[];
  p.on('pageerror',e=>errs.push(String(e).slice(0,300)));
  await ctx.route('**/*',r=>{const u=new URL(r.request().url());
    if(u.hostname==='127.0.0.1') return r.continue();
    if(u.hostname==='site.api.espn.com'){espn.push(u.pathname);return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:JSON.stringify(u.pathname.includes('/nfl/')?sb:{events:[]})});}
    blocked.add(u.hostname); return r.fulfill({status:404,headers:{'Access-Control-Allow-Origin':'*'},body:''});});
  return {ctx,p,errs,blocked,espn};
}
const hsc=p=>p.evaluate(()=>({sw:document.documentElement.scrollWidth,iw:innerWidth,wide:[...document.querySelectorAll('body *')].filter(e=>{const r=e.getBoundingClientRect();return r.right>innerWidth+1&&r.width>0&&getComputedStyle(e).position!=='fixed'}).slice(0,4).map(e=>e.tagName+'.'+e.className)}));
for (const w of [390,1280]) {
  const {ctx,p,errs,blocked,espn}=await mk({width:w,height:844});
  // fresh (no setup) pass
  await p.goto(B); await p.evaluate(()=>localStorage.clear());
  for (const r of ['#/welcome','#/','#/pair','#/settings']) { await p.goto(B+r); await p.reload(); await p.waitForTimeout(900);
    const h=await hsc(p); const txt=(await p.innerText('body')).length; log(w,'fresh',r,'textlen',txt,'scrollW',h.sw, h.sw>h.iw?'OVERFLOW '+h.wide:''); if(w===390&&h.sw>h.iw) fails.push(`${w} fresh ${r} overflow ${h.sw} ${h.wide}`); }
  // configured pass
  await p.evaluate(()=>{ localStorage.setItem('mcdtv_web_profile','p1'); localStorage.setItem('mcdtv_web_init', JSON.stringify({m3u_url:'http://127.0.0.1:8781/get.php?username=u&password=p&type=m3u_plus', tmdb_key:'x'})); });
  for (const r of ['#/','#/browse','#/live','#/list','#/settings','#/pair','#/welcome','#/profiles','#/search']) {
    const before=errs.length; await p.goto(B+r); await p.reload(); await p.waitForTimeout(r==='#/live'?3500:1200);
    const h=await hsc(p); const txt=(await p.innerText('#app, main, body').catch(()=>'')).replace(/\s+/g,' ').slice(0,70);
    log(w,'cfg',r,'scrollW',h.sw,h.sw>h.iw?'OVERFLOW '+h.wide:'ok','|',txt);
    if(errs.length>before) fails.push(`${w} ${r} pageerror: ${errs.slice(before).join('|')}`);
    if(w===390&&h.sw>h.iw) fails.push(`${w} ${r} horizontal overflow ${h.sw}>${h.iw} ${h.wide}`);
  }
  // Live sections + sports chips
  await p.goto(B+'#/live'); await p.reload(); await p.waitForTimeout(3500);
  const chips=await p.$$eval('#lvChips .chip',r=>r.map(x=>x.dataset.sec+':'+x.innerText.replace(/\s+/g,' ')));
  log(w,'chips',chips.join(' | '));
  for (const s of ['Sports','News','Entertainment','All','Favorites','Recent']) { await p.click(`#lvChips .chip[data-sec="${s}"]`).catch(e=>fails.push(`${w} chip ${s} missing`)); await p.waitForTimeout(300); log(w,'sec',s,'rows',await p.$$eval('.lv-row',r=>r.length)); }
  await p.click('#lvChips .chip[data-sec="Sports"]'); await p.waitForTimeout(300);
  for (const sub of await p.$$eval('.lv-sports .chip',r=>r.map(x=>x.dataset.sub))) { await p.click(`.lv-sports .chip[data-sub="${sub}"]`); await p.waitForTimeout(250); log(w,'sport',sub,await p.$$eval('.lv-row b',r=>r.map(x=>x.innerText).join(','))); }
  // Games rail
  const nfl=await p.$('#lvChips .chip[data-sec="game:NFL"]'); if(!nfl) fails.push(`${w} NFL games chip missing`); else {
    log(w,'NFL chip count',await nfl.innerText());
    await nfl.click(); await p.waitForTimeout(1200);
    const cards=await p.$$eval('.gm-card',r=>r.map(x=>x.innerText.replace(/\s+/g,' ')));
    log(w,'game cards',cards.length,cards.join(' || '), 'headers', await p.$$eval('.gm-h',r=>r.map(x=>x.innerText).join(',')), 'week', await p.$eval('.gm-week',e=>e.innerText).catch(()=>'none'));
    if(cards.length!==2) fails.push(`${w} expected 2 game cards got ${cards.length}`);
    await p.click('.gm-card .gm-main'); await p.waitForTimeout(500);
    const ch=await p.$$eval('.gm-card.open .lv-row b',r=>r.map(x=>x.innerText).join(','));
    log(w,'open game channels',ch); if(!/ESPN/.test(ch)) fails.push(`${w} live game did not list ESPN: "${ch}"`);
    const h=await hsc(p); if(w===390&&h.sw>h.iw) fails.push(`${w} games overflow ${h.sw} ${h.wide}`);
  }
  for (const L of ['NBA','NHL','EPL']) { const c=await p.$(`#lvChips .chip[data-sec="game:${L}"]`); log(w,'empty league chip',L,c?'still present':'removed'); }
  // Favorites star toggle
  await p.click('#lvChips .chip[data-sec="All"]'); await p.waitForTimeout(300);
  const fv=()=>p.evaluate(()=>{try{return JSON.parse(localStorage.mcdtv_web_plists).p1.live_favorites.length}catch{return 0}});
  const f0=await fv(); await p.click('.lv-row .lv-star'); await p.waitForTimeout(300); const f1=await fv();
  const star1=await p.$eval('.lv-row .lv-star',e=>e.innerText+'|'+e.getAttribute('aria-pressed'));
  await p.click('.lv-row .lv-star'); await p.waitForTimeout(300); const f2=await fv();
  log(w,'fav toggle',f0,f1,f2,'star after add',star1); if(!(f1===f0+1&&f2===f0)) fails.push(`${w} favorite toggle ${f0}->${f1}->${f2}`);
  // Settings > Check network: runs, lists TMDB, addons, the relay and the TV link (all mocked here), shows no keys
  await p.goto(B+'#/settings'); await p.reload(); await p.waitForTimeout(800);
  if(!await p.$('#netBtn')) fails.push(`${w} Check network button missing`); else {
    await p.click('#netBtn'); await p.waitForFunction(()=>!document.querySelector('#netBtn').disabled,null,{timeout:15000}).catch(()=>fails.push(`${w} network check never finished`));
    const net=await p.$$eval('#netList li',r=>r.map(x=>x.innerText.replace(/\s+/g,' ')));
    log(w,'netcheck',net.join(' || '));
    if(net.length<3) fails.push(`${w} network check listed ${net.length} rows`);
    if(net.some(x=>x.includes('Checking'))) fails.push(`${w} network check left a row pending`);
    if(net.some(x=>/api_key|password|username=/.test(x))) fails.push(`${w} network check shows a key or login`);
  }
  log(w,'pageerrors',errs.length?errs.join(' | '):'none','blocked hosts',[...blocked].join(','),'espn calls',espn.length);
  if(errs.length) fails.push(...errs.map(e=>`${w} pageerror ${e}`));
  await ctx.close();
}
log('FAILS', fails.length); fails.forEach(f=>log(' -',f));
await b.close();
process.exit(fails.length ? 1 : 0);
