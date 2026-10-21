// Throttled end-to-end test of the web player's quality fallback (QF in docs/app/index.html).
// Real HLS: mkstreams.sh makes a 6 Mbps "4K" stream and a 1.9 Mbps 1080p copy; srv.py serves them as live channels
// "US| ESPN 4K" and "US| ESPN FHD" (one channel, two copies) and "US| FOX 4K" (both in one multi-quality playlist).
// Chrome's network throttling (DevTools protocol) caps the download speed. Proves:
//   A. 4K playing, speed capped under its bitrate: it steps down to 1080p within seconds, then plays with no freeze.
//   B. Opening that channel again starts at 1080p (it froze here before), with no probe needed.
//   C. A fresh phone on a slow connection: the probe measures it and the channel opens at 1080p up front.
//   D. Speed wobbling across the bitrate: at most one step down, never back up.
//   E. Speed wobbling above the bitrate (1.25x to 1.4x): no switch at all (and fewer than 3 freezes, else it should have).
//   F. Multi-quality playlist: hls.js may climb to 4K; once it drops below 4K it never climbs back.
//   G. iPhone path (built-in HLS player, no speed figures; runs where Chromium plays HLS itself, as in CI): freezes
//      step it down, and reopening stays at 1080p even though the probe before the freeze measured a fast link.
// Every switch must be in the quality log (mcdtv_web_qlog). Run: bash mkstreams.sh; python3 srv.py & node qf-net.mjs
import { chromium } from 'playwright';
import fs from 'node:fs';
const B = 'http://127.0.0.1:8781/index.html';
const HLS = fs.readFileSync(new URL('./node_modules/hls.js/dist/hls.min.js', import.meta.url));
const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'], ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) });
const fails = [], log = (...a) => console.log(...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const MBPS = 1e6;

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1';
async function phone(name, seed, ua) {
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, ...(ua ? { userAgent: ua } : {}) });
  const p = await ctx.newPage();
  const errs = [];
  p.on('pageerror', (e) => errs.push(String(e).slice(0, 300)));
  await ctx.route('**/*', (r) => {
    const u = new URL(r.request().url());
    if (u.hostname === '127.0.0.1') return r.continue();
    if (u.hostname === 'cdn.jsdelivr.net' && u.pathname.includes('/hls.js@')) return r.fulfill({ status: 200, contentType: 'application/javascript', body: HLS });
    return r.fulfill({ status: 404, body: '' });
  });
  const cdp = await ctx.newCDPSession(p);
  await cdp.send('Network.enable');
  const net = async (bps) => cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 20, downloadThroughput: bps ? bps / 8 : -1, uploadThroughput: -1 });
  await p.goto(B);
  await p.evaluate((s) => { localStorage.clear(); for (const [k, v] of Object.entries(s)) localStorage.setItem(k, v); }, {
    mcdtv_web_profile: 'p1', mcdtv_web_quality: 'auto',
    mcdtv_web_init: JSON.stringify({ m3u_url: 'http://127.0.0.1:8781/get.php?username=qf&password=p&type=m3u_plus', tmdb_key: 'x' }), ...(seed || {}) });
  await p.goto(B + '#/live'); await p.reload();
  await p.waitForSelector('.lv-row', { timeout: 20000 });
  return { name, ctx, p, net, errs };
}
/** Opens a channel by its place in the test list (0 ESPN 4K, 1 ESPN FHD, 2 FOX 4K) and waits for the picture. */
async function open(ph, idx) {
  await ph.p.evaluate(() => { const c = document.querySelector('#lvChips .chip[data-sec="All"]'); if (c) c.click(); });
  await ph.p.waitForSelector(`.lv-row[data-idx="${idx}"]`, { timeout: 10000 });
  await ph.p.evaluate((i) => document.querySelector(`.lv-row[data-idx="${i}"]`).click(), idx);
  await ph.p.waitForFunction(() => { const v = document.querySelector('#lvVid'); return v && v.currentTime > 1; }, null, { timeout: 30000 });
  // Count freezes from here on (time of each "waiting" while playing).
  await ph.p.evaluate(() => { const v = document.querySelector('#lvVid'); window.__w = []; v.addEventListener('waiting', () => { if (!v.paused) window.__w.push(Date.now()); }); });
}
const qlog = (ph) => ph.p.evaluate(() => JSON.parse(localStorage.getItem('mcdtv_web_qlog') || '[]').reverse());
const state = (ph) => ph.p.evaluate(() => { const v = document.querySelector('#lvVid'); return { t: v ? v.currentTime : -1, line: (document.querySelector('#lvLine') || {}).textContent || '', waits: (window.__w || []).slice() }; });
const close = (ph) => ph.p.evaluate(() => { const x = document.querySelector('#lvBack'); if (x) x.click(); });
const fmt = (l) => l.map((e) => `${e.name}: ${e.from}->${e.to} ${e.why || e.note || ''} est=${(e.est / MBPS || 0).toFixed(1)} br=${(e.br / MBPS || 0).toFixed(1)}`).join(' | ');
/** Plays on for ms and checks the picture kept moving with no freeze after the given time. */
async function noFreeze(ph, label, ms, since) {
  const a = await state(ph); await sleep(ms); const z = await state(ph);
  const moved = z.t - a.t, frozen = z.waits.filter((t) => t > since);
  log(`  ${label}: played ${moved.toFixed(1)} s in ${ms / 1000} s, freezes ${frozen.length}`);
  if (moved < ms / 1000 - 3) fails.push(`${ph.name} ${label}: picture moved only ${moved.toFixed(1)} s in ${ms / 1000} s`);
  if (frozen.length) fails.push(`${ph.name} ${label}: ${frozen.length} freezes`);
}

// A + B: react, then remember.
{
  const ph = await phone('A');
  await ph.net(0);
  await open(ph, 0);
  await sleep(8000);
  let s = await state(ph);
  log('A player:', await ph.p.evaluate(() => (window.Hls ? 'hls.js ' + window.Hls.version : 'built-in')));
  log('A 4K playing unthrottled:', s.line, 'log', fmt(await qlog(ph)));
  if (/1080p/.test(s.line)) fails.push('A: opened at 1080p on a fast connection');
  const t0 = Date.now();
  await ph.net(3 * MBPS); // half the 4K stream's bitrate
  let sw = null;
  while (Date.now() - t0 < 30000 && !sw) { await sleep(500); sw = (await qlog(ph)).find((e) => e.from === '4K' && /1080p/.test(e.to)); }
  if (!sw) fails.push('A: no step down within 30 s at 3 Mbps; log: ' + fmt(await qlog(ph)));
  else {
    const secs = (sw.t - t0) / 1000;
    log(`A stepped down after ${secs.toFixed(1)} s (${sw.why}); log`, fmt(await qlog(ph)));
    if (secs > 12) fails.push(`A: step down took ${secs.toFixed(1)} s`);
    await sleep(4000); // the new copy loads
    s = await state(ph);
    if (!/1080p/.test(s.line)) fails.push('A: player line does not say 1080p: ' + s.line);
    await noFreeze(ph, 'A after step down at 3 Mbps', 20000, Date.now());
  }
  // B: open it again: 1080p up front, because it froze here.
  await close(ph); await sleep(500);
  await open(ph, 0);
  const lb = await qlog(ph), last = lb[lb.length - 1];
  s = await state(ph);
  log('B reopen:', s.line, '|', fmt([last]));
  if (!last || last.why !== 'froze_before' || !/1080p/.test(s.line)) fails.push('B: reopen did not start at 1080p (froze_before): ' + s.line + ' ' + fmt([last || {}]));
  await noFreeze(ph, 'B 1080p at 3 Mbps', 10000, Date.now());
  if (ph.errs.length) fails.push('A/B page errors: ' + ph.errs.join(' | '));
  await ph.ctx.close();
}

// C: a fresh phone on a slow connection picks 1080p before it plays.
{
  const ph = await phone('C');
  await ph.net(3 * MBPS);
  await open(ph, 0);
  const l = await qlog(ph), s = await state(ph);
  log('C fresh at 3 Mbps:', s.line, '|', fmt(l));
  if (!l.length || l[0].why !== 'slow_network' || !/1080p/.test(s.line)) fails.push('C: no up-front 1080p from the probe: ' + s.line + ' ' + fmt(l));
  await noFreeze(ph, 'C 1080p at 3 Mbps', 15000, Date.now());
  if (ph.errs.length) fails.push('C page errors: ' + ph.errs.join(' | '));
  await ph.ctx.close();
}

/** Speed that changes every 3 s between two values, for ms. */
async function wobble(ph, lo, hi, ms) { const end = Date.now() + ms; let k = 0; while (Date.now() < end) { await ph.net(k++ % 2 ? hi : lo); await sleep(3000); } }

// D: wobble across the 6 Mbps bitrate (5.6 and 6.6 Mbps): one step down at most, never back.
{
  const ph = await phone('D');
  await ph.net(0); await open(ph, 0); await sleep(6000);
  await wobble(ph, 5.6 * MBPS, 6.6 * MBPS, 45000);
  const l = (await qlog(ph)).filter((e) => e.from !== e.to);
  log('D wobble 5.6/6.6 Mbps:', (await state(ph)).line, '|', fmt(l));
  if (l.length > 1) fails.push('D: more than one quality change: ' + fmt(l));
  if (l.some((e) => e.to === '4K')) fails.push('D: went back up to 4K: ' + fmt(l));
  if (ph.errs.length) fails.push('D page errors: ' + ph.errs.join(' | '));
  await ph.ctx.close();
}

// E: wobble above the bitrate (7.5 and 8.5 Mbps, 1.25x to 1.4x): stays on 4K, no switch, no freeze.
{
  const ph = await phone('E');
  await ph.net(0); await open(ph, 0); await sleep(4000);
  const since = Date.now();
  await wobble(ph, 7.5 * MBPS, 8.5 * MBPS, 36000);
  const l = (await qlog(ph)).filter((e) => e.from !== e.to), s = await state(ph);
  log('E wobble 7.5/8.5 Mbps:', s.line, '| changes', fmt(l) || 'none', '| freezes', s.waits.filter((t) => t > since).length);
  if (l.length) fails.push('E: switched with enough speed: ' + fmt(l));
  // A live stream near its edge (about 6 s of buffer) can still freeze once or twice on a link with only 25% to 40%
  // headroom. Under 3 in a minute the monitor holds 4K by design; 3 or more without a step down would be a bug.
  if (s.waits.filter((t) => t > since).length >= 3) fails.push('E: 3 or more freezes and no step down');
  if (ph.errs.length) fails.push('E page errors: ' + ph.errs.join(' | '));
  await ph.ctx.close();
}

// F: one multi-quality playlist (2160 and 1080 levels): after the first drop below 4K, never back up.
{
  const ph = await phone('F');
  await ph.net(0); await open(ph, 2); await sleep(12000);
  await wobble(ph, 3 * MBPS, 9 * MBPS, 42000);
  const l = (await qlog(ph)).filter((e) => e.name && /FOX/.test(e.name) && e.from !== e.to);
  log('F multi-quality:', fmt(l) || 'no switches');
  const drop = l.findIndex((e) => parseInt(e.from) >= 1600 || e.from === '4K');
  if (drop >= 0 && l.slice(drop + 1).some((e) => parseInt(e.to) >= 1600 || e.to === '4K')) fails.push('F: climbed back to 4K after a drop: ' + fmt(l));
  if (!l.length) log('  (hls.js stayed on one level; nothing to lock)');
  if (ph.errs.length) fails.push('F page errors: ' + ph.errs.join(' | '));
  await ph.ctx.close();
}

// G: the iPhone path.
{
  const ph = await phone('G', null, IPHONE);
  const can = await ph.p.evaluate(() => document.createElement('video').canPlayType('application/vnd.apple.mpegurl'));
  if (!can) log('G skipped: this Chromium has no built-in HLS player (CI has one)');
  else {
    await ph.net(0); await open(ph, 0); await sleep(6000);
    log('G player:', await ph.p.evaluate(() => (window.Hls ? 'hls.js' : 'built-in')), '|', (await state(ph)).line, '|', fmt(await qlog(ph)));
    const t0 = Date.now(); await ph.net(3 * MBPS);
    let sw = null;
    while (Date.now() - t0 < 45000 && !sw) { await sleep(500); sw = (await qlog(ph)).find((e) => e.from === '4K' && /1080p/.test(e.to)); }
    if (!sw) fails.push('G: no step down within 45 s at 3 Mbps (built-in player); log: ' + fmt(await qlog(ph)));
    else {
      log(`G stepped down after ${((sw.t - t0) / 1000).toFixed(1)} s (${sw.why})`);
      await sleep(4000);
      const a = await state(ph); await sleep(15000); const z = await state(ph);
      log(`  G after step down: played ${(z.t - a.t).toFixed(1)} s in 15 s`);
      if (z.t - a.t < 7) fails.push(`G: 1080p copy barely played (${(z.t - a.t).toFixed(1)} s in 15 s)`);
      await close(ph); await sleep(500);
      await open(ph, 0);
      const l = await qlog(ph), last = l[l.length - 1], s = await state(ph);
      log('G reopen:', s.line, '|', fmt([last]));
      if (!last || last.why !== 'froze_before' || !/1080p/.test(s.line)) fails.push('G: reopen on the iPhone path did not stay at 1080p: ' + s.line + ' ' + fmt([last || {}]));
    }
  }
  if (ph.errs.length) fails.push('G page errors: ' + ph.errs.join(' | '));
  await ph.ctx.close();
}

log('FAILS', fails.length); fails.forEach((f) => log(' -', f));
await b.close();
process.exit(fails.length ? 1 : 0);
