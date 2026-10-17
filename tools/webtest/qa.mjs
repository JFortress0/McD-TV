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
async function mk(vp,opts){
  const ctx=await b.newContext({viewport:vp,...(opts||{})}); const p=await ctx.newPage();
  const errs=[], blocked=new Set(), espn=[];
  p.on('pageerror',e=>errs.push(String(e).slice(0,300)));
  await ctx.route('**/*',r=>{const u=new URL(r.request().url());
    if(u.hostname==='127.0.0.1') return r.continue();
    // Settings > Check network: TMDB and the TV link (ntfy.sh) answer 200, so the "OK" path is tested.
    if(u.hostname==='api.themoviedb.org'&&u.pathname==='/3/configuration') return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:'{"images":{}}'});
    if(u.hostname==='ntfy.sh'&&u.pathname==='/v1/health') return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:'{"healthy":true}'});
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
    for (const name of ['TMDB','TV link']) { const row=net.find(x=>x.includes(name)); if(!row||!/^OK\b/.test(row)) fails.push(`${w} network check: ${name} not OK: "${row||'missing'}"`); }
  }
  // Recent errors: an uncaught error is logged without its query string, shown in Settings, and cleared
  await p.evaluate(()=>{ try{logErr('qa boom https://example.com/x?api_key=SECRET123','qa.js:1');}catch{} });
  await p.goto(B+'#/settings'); await p.reload(); await p.waitForTimeout(800);
  const el=await p.$eval('#errList',e=>e.innerText).catch(()=>'missing');
  log(w,'recent errors',el.replace(/\s+/g,' ').slice(0,120));
  if(!el.includes('qa boom')) fails.push(`${w} recent errors list missing the logged error`);
  if(/SECRET|api_key/.test(el)) fails.push(`${w} recent errors list shows a key`);
  await p.click('#errClear').catch(()=>fails.push(`${w} recent errors Clear missing`)); await p.waitForTimeout(200);
  if(!/No errors recorded/.test(await p.$eval('#errList',e=>e.innerText).catch(()=>''))) fails.push(`${w} recent errors not cleared`);
  // Service worker: shell cache named after BUILD
  const sw=await p.evaluate(async()=>{ try{ await navigator.serviceWorker.ready; await new Promise(r=>setTimeout(r,500)); return {keys:await caches.keys(),build:BUILD}; }catch(e){ return {err:String(e)}; } });
  log(w,'sw caches',JSON.stringify(sw));
  if(!sw.keys||!sw.keys.includes('jarvis-shell-'+sw.build)) fails.push(`${w} service worker cache not tied to BUILD: ${JSON.stringify(sw)}`);
  // Watchlist edits while the TV is off: queued, shown in the list, kept until the TV confirms
  const wq=await p.evaluate(async()=>{ const link=S.link, key=S.key; S.link=null; S.key=null; localStorage.removeItem('mcdtv_web_wlq');
    await WLQ.send({cmd:'watchlist',type:'movie',id:603,on:true,name:'QA'},'p1'); await WLQ.send({cmd:'watchlist',type:'movie',id:604,on:true},'p1'); await WLQ.send({cmd:'watchlist',type:'movie',id:604,on:false},'p1');
    const L={watchlist:[{type:'movie',id:604}]}; WLQ.overlay('p1',L); const n=WLQ.size(); localStorage.removeItem('mcdtv_web_wlq'); S.link=link; S.key=key;
    return {n,wl:L.watchlist.map(r=>r.id).join(',')}; });
  log(w,'watchlist queue',JSON.stringify(wq));
  if(wq.n!==2||wq.wl!=='603') fails.push(`${w} watchlist queue ${JSON.stringify(wq)}`);
  log(w,'pageerrors',errs.length?errs.join(' | '):'none','blocked hosts',[...blocked].join(','),'espn calls',espn.length);
  if(errs.length) fails.push(...errs.map(e=>`${w} pageerror ${e}`));
  await ctx.close();
}
// Live TV extras: Huntsville call signs in Local, guide cache kept across launches, Tap to play when autoplay is blocked.
{
  const {ctx,p,errs}=await mk({width:390,height:844});
  await ctx.addInitScript(()=>{ HTMLMediaElement.prototype.play=function(){ return Promise.reject(new DOMException('blocked','NotAllowedError')); };
    const cpt=HTMLMediaElement.prototype.canPlayType; HTMLMediaElement.prototype.canPlayType=function(t){ return /mpegurl/i.test(t)?'maybe':cpt.call(this,t); }; });
  await p.goto(B); await p.evaluate(()=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1'); localStorage.setItem('mcdtv_web_init', JSON.stringify({m3u_url:'http://127.0.0.1:8781/get.php?username=u&password=p&type=m3u_plus', tmdb_key:'x'})); });
  const secs=await p.evaluate(()=>[['USA','WHNT News 19'],['US| NEWS','WAAY 31 News'],['US| NEWS','CNN'],['US| NEWS','WHNTX News']].map(([g,n])=>LO.section(g,n)));
  log('LO huntsville',secs.join(',')); if(secs.join()!=='Local,Local,News,News') fails.push(`LO Huntsville sections ${secs}`);
  await p.goto(B+'#/live'); await p.reload(); await p.waitForTimeout(6000);
  const saved=await p.evaluate(()=>localStorage.getItem('mcdtv_web_epg')||'');
  let rows=0; try{ rows=Object.keys(JSON.parse(saved).rows).length; }catch{}
  log('epg cache rows',rows,'bytes',saved.length); if(!rows) fails.push('guide cache not saved'); if(/password|username/.test(saved)) fails.push('guide cache holds the playlist login');
  await p.reload(); await p.waitForTimeout(1500);
  const loading=await p.$$eval('.lv-row',r=>r.filter(x=>/Loading guide/.test(x.innerText)).length);
  log('rows still loading after relaunch',loading);
  await ctx.route('**/live/**',()=>{}); // the stream never answers, so only the blocked play() is tested
  await p.click('.lv-row b'); await p.waitForTimeout(1500);
  const tap=await p.$eval('#lvTap',e=>!e.classList.contains('hidden')).catch(()=>false);
  const msgShown=await p.$eval('#lvMsg',e=>!e.classList.contains('hidden')).catch(()=>false);
  log('live tap to play shown',tap,'error shown',msgShown,await p.$eval('#lvMsgText',e=>e.innerText).catch(()=>'')); if(!tap||msgShown) fails.push(`live Tap to play: tap ${tap} msg ${msgShown}`);
  if(errs.length) fails.push(...errs.map(e=>`extras pageerror ${e}`));
  await ctx.close();
}
// Safe updates: a new version reloads right away when idle, and waits with "Update ready" during a source lookup.
{
  const {ctx,p,errs}=await mk({width:390,height:844},{serviceWorkers:'block'});
  await ctx.route(/127\.0\.0\.1:\d+\/index\.html\?u=/,r=>r.fulfill({status:200,headers:{'Content-Type':'text/html'},body:'<script>const BUILD = "qa-next";</script>'}));
  await p.goto(B); await p.evaluate(()=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1'); localStorage.setItem('mcdtv_web_init', JSON.stringify({tmdb_key:'x'})); });
  await p.goto(B+'#/sources/movie/603'); await p.reload(); await p.evaluate(()=>{ window.__qaMark=1; });
  const chip=await p.waitForSelector('#updChip',{timeout:8000}).catch(()=>null);
  const kept=await p.evaluate(()=>window.__qaMark===1).catch(()=>false);
  log('update during source lookup: chip',chip?await chip.innerText():'none','page kept',kept);
  if(!chip) fails.push('update prompt did not show during a source lookup'); if(!kept) fails.push('update reloaded during a source lookup');
  if(chip){ const nav=p.waitForEvent('load',{timeout:8000}).then(()=>true).catch(()=>false); await chip.click(); if(!await nav) fails.push('tapping Update ready did not reload'); }
  await p.goto(B+'#/settings'); await p.waitForTimeout(500); await p.evaluate(()=>{ window.__qaMark=1; });
  await p.waitForTimeout(4500);
  const idle=await p.evaluate(()=>window.__qaMark===1).catch(()=>false);
  log('update when idle: reloaded',!idle);
  if(idle) fails.push('update did not apply when idle');
  if(errs.length) fails.push(...errs.map(e=>`update test pageerror ${e}`));
  await ctx.close();
}
// Find my TVs: the house link from the Control page's "mcdtv_tvs", then "tv_who" on the house topic and the TVs'
// "tv_link" answers (mocked here, encrypted with a test house key) relink the phone and update "mcdtv_tvs".
{
  const rb=(n)=>{ const a=new Uint8Array(n); globalThis.crypto.getRandomValues(a); return Buffer.from(a).toString('base64url'); };
  const HID='qahouse'+Date.now().toString(36), HK=rb(32), house=HID+'.'+HK;
  const OLD={id:'oldfiretvaa22',key:rb(32)}, NEW={id:'newfiretvbb22',key:rb(32)}, GTV={id:'googletvcc22',key:rb(32)};
  const keysAll=[HK,OLD.key,NEW.key,GTV.key];
  let reply=''; const posts=[];
  const ntfyMock=async(ctx)=>ctx.route(/^https:\/\/ntfy\.sh\/mcdtv-qahouse/,r=>{ const q=r.request(), u=new URL(q.url());
    if(q.method()==='POST'){ posts.push(q.postData()||''); return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:'{}'}); }
    if(u.pathname.endsWith('/json')) return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/x-ndjson'},body:reply});
    return r.fulfill({status:404,headers:{'Access-Control-Allow-Origin':'*'},body:''}); });
  const seed=(p,link)=>p.evaluate(([house,OLD,link])=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1');
    localStorage.setItem('mcdtv_web_init',JSON.stringify({tmdb_key:'x'}));
    localStorage.setItem('mcdtv_tvs',JSON.stringify([{id:OLD.id,key:OLD.key,name:'Fire TV',house}]));
    if(link) localStorage.setItem('mcdtv_link',OLD.id+'.'+OLD.key); },[house,OLD,link]);
  // (a) + (b): linked to an old Fire TV link that no longer answers.
  {
    const {ctx,p,errs}=await mk({width:390,height:844}); await ntfyMock(ctx);
    await p.goto(B); await seed(p,true);
    const t=Date.now();
    reply=(await p.evaluate(async([HK,NEW,GTV,t])=>{ const k=await importKey(HK); const ev=async(o,i)=>JSON.stringify({id:'m'+i,time:Math.floor(t/1000),event:'message',topic:'x',message:await encrypt(o,k)});
      return [await ev({type:'sync',ts:t},0), await ev({type:'tv_link',from:'devfire',name:'Fire TV',link:'bad.link',ts:t-1000},1),
        await ev({type:'tv_link',from:'devfire',name:'Fire TV',link:NEW.id+'.'+NEW.key,ts:t-500},2),
        await ev({type:'tv_link',from:'devgoogle',name:'Google TV',link:GTV.id+'.'+GTV.key,ts:t},3)].join('\n')+'\n'; },[HK,NEW,GTV,t]));
    await p.reload(); await p.waitForTimeout(800);
    const a=await p.evaluate(()=>({ready:RC.ready(),house:!!(S.init&&S.init.house),saved:!!(JSON.parse(localStorage.mcdtv_web_init||'{}').house)}));
    log('find tvs (a) house from mcdtv_tvs',JSON.stringify(a));
    if(!a.ready||!a.house||!a.saved) fails.push(`find tvs (a): house link not adopted from mcdtv_tvs ${JSON.stringify(a)}`);
    const r=await p.evaluate(async()=>{ const r=await findTvs(); await new Promise(z=>setTimeout(z,300));
      return {n:r.tvs.length,link:S.link&&S.link.id,tvs:JSON.parse(localStorage.mcdtv_tvs||'[]').map(t=>t.id+':'+(t.dev||'')+':'+t.name),stored:localStorage.mcdtv_link.split('.')[0],house:!!(S.init&&S.init.house),toast:document.querySelector('#toast').innerText,errs:localStorage.mcdtv_web_errors||''}; });
    log('find tvs (b)',JSON.stringify({...r,errs:r.errs.length}));
    if(r.n!==2) fails.push(`find tvs (b): expected 2 TVs, got ${r.n}`);
    if(r.link!==NEW.id||r.stored!==NEW.id) fails.push(`find tvs (b): link not switched to the same-name TV (${r.link})`);
    if(!r.tvs.includes(NEW.id+':devfire:Fire TV')||!r.tvs.includes(GTV.id+':devgoogle:Google TV')) fails.push(`find tvs (b): mcdtv_tvs not updated ${r.tvs}`);
    if(!r.house) fails.push('find tvs (b): house link lost after relinking');
    if(!/Found your Fire TV\. Linked again\./.test(r.toast)) fails.push(`find tvs (b): toast "${r.toast}"`);
    const leak=keysAll.find(k=>r.toast.includes(k)||r.errs.includes(k)); if(leak) fails.push('find tvs (b): a key shows in a toast or the error log');
    const who=await p.evaluate(async([HK,msgs])=>{ const k=await importKey(HK); const out=[]; for(const m of msgs){ const o=await decrypt(m,k); if(o) out.push(o.type+':'+typeof o.from+':'+typeof o.ts); } return out; },[HK,posts]);
    log('find tvs posts',who.join(',')); if(!who.length||!who.every(x=>x==='tv_who:string:number')) fails.push(`find tvs: tv_who post ${who}`);
    if(errs.length) fails.push(...errs.map(e=>`find tvs pageerror ${e}`));
    await ctx.close();
  }
  // (c): nobody answers: Settings > Find my TVs says so.
  {
    reply='';
    const {ctx,p,errs}=await mk({width:390,height:844}); await ntfyMock(ctx);
    await p.goto(B); await seed(p,false);
    await p.goto(B+'#/settings'); await p.reload(); await p.waitForTimeout(800);
    if(!await p.$('#findTvBtn')) fails.push('find tvs (c): Find my TVs button missing'); else {
      await p.click('#findTvBtn');
      await p.waitForFunction(()=>!document.querySelector('#findTvBtn').disabled,null,{timeout:20000}).catch(()=>fails.push('find tvs (c): never finished'));
      const txt=await p.$eval('#tvFound',e=>e.innerText.replace(/\s+/g,' ')).catch(()=>'');
      log('find tvs (c) no answer',txt);
      if(!txt.includes('No TV answered. TVs need the latest Jarvis update to answer here. Open Jarvis on a TV and try again.')) fails.push(`find tvs (c): no-TV message missing: "${txt}"`);
      const h=await hsc(p); if(h.sw>h.iw) fails.push(`find tvs (c): settings overflow ${h.sw} ${h.wide}`);
    }
    if(errs.length) fails.push(...errs.map(e=>`find tvs (c) pageerror ${e}`));
    await ctx.close();
  }
}
log('FAILS', fails.length); fails.forEach(f=>log(' -',f));
await b.close();
process.exit(fails.length ? 1 : 0);
