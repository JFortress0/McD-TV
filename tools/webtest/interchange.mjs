// Replays the October 2026 failure: The Irishman (209 minutes) on a file that dies at 34:50, then the same title
// on a healthy file, on the web app with a real video. Nothing real is called: TMDB, the addon, Real-Debrid
// (through the Jarvis relay), the resume store and the video host are mocked here.
//
// Sources (all cached on the mocked Real-Debrid):
//   A  1080p, ranked first. Its header says 3:29:00, but the bytes stop at 34:50: the real failure.
//   C  1080p. Real-Debrid's media info says 34:50: a short file that betrays itself before it plays.
//   B  720p, full length: healthy.
// Checks: the web session dies at 34:50 and says so; the spot is saved with the title's length (not "watched");
// A is flagged for the house; Best source skips C on its length and plays B at the saved spot (within 5 s).
// The records the web app wrote (resume record, flags, the source list) go to interchange-fixture.json, which the
// TV's unit test (InterchangeFixtureTest.kt) replays on the TV side. UPDATE_FIXTURE=1 rewrites it.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = path.join(HERE, 'interchange-fixture.json');
const MEDIA = fs.readFileSync(path.join(HERE, 'media', 'irishman-209min.mp4'));
// ffprobe: the first packet at 34:50 (2090 s) starts at this byte. Bytes from here on are "missing".
const CUT_BYTE = 83473, CUT_MS = 2090 * 1000, RUNTIME_MIN = 209, TMDB_ID = 398978, IMDB = 'tt1302006';
const H = { A: 'a1'.repeat(20), C: 'c3'.repeat(20), B: 'b2'.repeat(20) };
const RD_LEN_S = { A: 12540, C: 2090, B: 12480 }; // what Real-Debrid's streaming/mediaInfos reports per file
const streams = [
  { name: 'QA Addon\n1080p', title: 'The.Irishman.2019.1080p.WEB-DL.DDP5.1.H264-GRPA\n💾 8.1 GB', infoHash: H.A, fileIdx: 0 },
  { name: 'QA Addon\n1080p', title: 'The.Irishman.2019.1080p.WEBRip.x264-GRPC\n💾 3.2 GB', infoHash: H.C, fileIdx: 0 },
  { name: 'QA Addon\n720p', title: 'The.Irishman.2019.720p.WEBRip.x264-GRPB\n💾 2.0 GB', infoHash: H.B, fileIdx: 0 },
];

const json = (o, status = 200) => ({ status, headers: { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json' }, body: JSON.stringify(o) });

/** Serves [buf] with byte ranges, like a real file host. */
function media(route, buf) {
  const range = route.request().headers()['range'];
  const base = { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes' };
  const m = range && /bytes=(\d+)-(\d*)/.exec(range);
  if (!m) return route.fulfill({ status: 200, headers: { ...base, 'Content-Length': String(buf.length) }, body: buf });
  const a = +m[1], b = m[2] ? Math.min(+m[2], buf.length - 1) : buf.length - 1;
  if (a >= buf.length) return route.fulfill({ status: 416, headers: { ...base, 'Content-Range': `bytes */${buf.length}` }, body: '' });
  return route.fulfill({ status: 206, headers: { ...base, 'Content-Range': `bytes ${a}-${b}/${buf.length}`, 'Content-Length': String(b - a + 1) }, body: buf.subarray(a, b + 1) });
}

export async function run({ executablePath, fails, log }) {
  const b = await chromium.launch({ ...(executablePath ? { executablePath } : {}), args: ['--autoplay-policy=no-user-gesture-required'] });
  const ctx = await b.newContext({ viewport: { width: 1280, height: 800 } });
  const p = await ctx.newPage();
  const errs = [], calls = [];
  p.on('pageerror', (e) => errs.push(String(e).slice(0, 300)));
  const store = {}; // the resume store: "h|p" -> { key tag: {v, t} }
  const houseKey = crypto.randomBytes(32).toString('base64url');
  await ctx.route('**/*', async (r) => {
    const req = r.request(), u = new URL(req.url());
    if (u.hostname === '127.0.0.1') return r.continue();
    if (u.hostname === 'api.themoviedb.org') {
      if (u.pathname === `/3/movie/${TMDB_ID}`) return r.fulfill(json({ id: TMDB_ID, title: 'The Irishman', runtime: RUNTIME_MIN, imdb_id: IMDB, release_date: '2019-11-01',
        original_language: 'en', origin_country: ['US'], production_countries: [{ iso_3166_1: 'US' }], genres: [{ id: 18, name: 'Drama' }], overview: '', vote_average: 7.8, vote_count: 9000 }));
      return r.fulfill(json({ results: [], images: {} }));
    }
    if (u.hostname === 'addon.test') {
      if (u.pathname.endsWith('/manifest.json')) return r.fulfill(json({ id: 'qa.addon', name: 'QA Addon', resources: ['stream'], types: ['movie'] }));
      if (u.pathname === `/stream/movie/${IMDB}.json`) return r.fulfill(json({ streams }));
      return r.fulfill(json({ streams: [] }));
    }
    if (u.hostname === 'media.test') {
      calls.push('media ' + u.pathname);
      return media(r, u.pathname === '/A.mp4' ? MEDIA.subarray(0, CUT_BYTE) : MEDIA);
    }
    if (u.hostname === 'jarvis-rd.netlify.app' && u.pathname === '/resume') {
      const k = u.searchParams.get('h') + '|' + u.searchParams.get('p');
      if (req.method() === 'POST') {
        const o = JSON.parse(req.postData() || '{}');
        const e = (store[k] = store[k] || {});
        if (!e[o.k] || +e[o.k].t < +o.t) e[o.k] = { v: o.v, t: o.t };
        return r.fulfill(json({ ok: true }));
      }
      return r.fulfill(json({ e: store[k] || {} }));
    }
    if (u.hostname === 'jarvis-rd.netlify.app' && u.pathname.startsWith('/rd/rest/1.0/')) {
      const rp = u.pathname.slice('/rd/rest/1.0'.length);
      calls.push('rd ' + req.method() + ' ' + rp);
      const which = (id) => String(id).slice(1); // TA -> A
      if (rp === '/torrents') return r.fulfill(json(Object.keys(H).map((x) => ({ id: 'T' + x, hash: H[x], status: 'downloaded', progress: 100, filename: 'The.Irishman.2019.mp4' }))));
      if (rp.startsWith('/torrents/info/')) {
        const x = which(rp.split('/').pop());
        return r.fulfill(json({ id: 'T' + x, hash: H[x], status: 'downloaded', progress: 100,
          files: [{ id: 1, path: `/The.Irishman.2019.${x}.mp4`, bytes: 2e9, selected: 1 }], links: [`https://real-debrid.com/d/${x}`] }));
      }
      if (rp === '/unrestrict/link') {
        const x = new URLSearchParams(req.postData() || '').get('link').split('/').pop();
        return r.fulfill(json({ id: 'U' + x, download: `https://media.test/${x}.mp4`, filename: `The.Irishman.2019.${x}.mp4`, mimeType: 'video/mp4', streamable: 0 }));
      }
      if (rp.startsWith('/streaming/mediaInfos/')) return r.fulfill(json({ duration: RD_LEN_S[which(rp.split('/').pop())] }));
      if (rp === '/user') return r.fulfill(json({ username: 'qa', type: 'premium' }));
      return r.fulfill(json({ error: 'unknown' }, 404));
    }
    return r.fulfill({ status: 404, headers: { 'Access-Control-Allow-Origin': '*' }, body: '' });
  });

  const B = 'http://127.0.0.1:8781/index.html';
  await p.goto(B);
  await p.evaluate(([hk]) => {
    localStorage.clear();
    localStorage.setItem('mcdtv_web_profile', 'p1');
    localStorage.setItem('mcdtv_web_init', JSON.stringify({ tmdb_key: 'x', house: 'qahouse.' + hk, addon_urls: ['https://addon.test/manifest.json'] }));
    localStorage.setItem('mcdtv_web_rdt', 'QA_RD_TOKEN');
  }, [houseKey]);

  const fail = (m) => { fails.push('interchange: ' + m); log('interchange FAIL', m); };
  const play = () => p.evaluate(() => { const v = document.querySelector('#vid'); return v ? { t: v.currentTime, d: v.duration, line: (document.querySelector('#plLine') || {}).textContent || '', msg: (document.querySelector('#plMsg:not(.hidden) #plMsgText') || {}).textContent || '', hash: location.hash } : { hash: location.hash }; });
  try {
    // 1. The source list, and each file's length against the title's 209 minutes.
    await p.goto(B + `#/sources/movie/${TMDB_ID}`); await p.reload();
    await p.waitForSelector('.src[data-src]', { timeout: 20000 });
    const rows = await p.$$eval('.src[data-src]', (a) => a.map((x) => x.title.replace(/\s+/g, ' ')));
    log('interchange sources', rows.map((r) => (/GRP\w/.exec(r) || [''])[0]).join(' | '));
    const table = await p.evaluate(async ([srcs, lens, rt]) => Promise.all(srcs.map(async (s) => {
      const k = await SH.key({ addon: 'QA Addon', ...s }), len = lens[s.title.split('-GRP')[1][0]] * 1000;
      return { group: 'GRP' + s.title.split('-GRP')[1][0], key: k.slice(0, 10) + '…', length: fmtTime(len / 1000), runtime: fmtTime(rt * 60), short: SH.shortSource(len, rt * 60000) };
    })), [streams, RD_LEN_S, RUNTIME_MIN]);
    for (const t of table) log('interchange length', t.group, t.length, 'of', t.runtime, t.short ? 'SHORT (flag before playing)' : 'ok by length');
    const rowA = rows.findIndex((r) => /GRPA/.test(r));
    if (rowA < 0) fail('file A is not listed');

    // 2. John's web session: picks A, skips to 34:40, and it dies at 34:50.
    await p.click(`.src[data-src="${rowA}"]`);
    await p.waitForFunction(() => { const v = document.querySelector('#vid'); return v && v.readyState >= 1; }, null, { timeout: 30000 });
    await p.evaluate(() => { const v = document.querySelector('#vid'); v.currentTime = 2080; v.play().catch(() => {}); });
    await p.waitForFunction(() => /appears incomplete/.test((document.querySelector('#plMsg:not(.hidden) #plMsgText') || {}).textContent || ''), null, { timeout: 120000 });
    const died = await play();
    log('interchange web died', JSON.stringify(died));
    // The player's clock may sit at the false end the browser jumped to; the stop is what it reports and saves.
    const said = /stops at (\d+):(\d\d)/.exec(died.msg);
    const saidMs = said ? (+said[1] * 60 + +said[2]) * 1000 : -1;
    log('interchange browser clock after the data ran out', died.t, 's (header length', died.d, 's)');
    if (Math.abs(saidMs - CUT_MS) > 5000) fail(`web says it stopped at ${saidMs} ms, expected about ${CUT_MS}`);
    if (!/stops at 34:\d\d/.test(died.msg)) fail(`message should name the stop: "${died.msg}"`);

    // 3. What the web app saved for the house.
    const rec = await p.evaluate(async (id) => (await RC.list('p1', true)).find((x) => x.type === 'movie' && +x.id === id) || null, TMDB_ID);
    const flags = await p.evaluate(async () => RC.badGet('movie:398978'));
    log('interchange saved', JSON.stringify(rec && { pos: rec.pos, dur: rec.dur }), 'flags', JSON.stringify(flags.map((f) => ({ k: f.k.slice(0, 10), s: f.s, n: f.n }))));
    if (!rec) fail('no resume record in the house store');
    else {
      if (Math.abs(rec.pos - CUT_MS) > 5000) fail(`saved position ${rec.pos} not at the stop`);
      if (rec.dur !== RUNTIME_MIN * 60000) fail(`saved length ${rec.dur}, expected the title's ${RUNTIME_MIN * 60000}`);
      if (rec.pos / rec.dur > 0.92) fail('the stop was saved as watched');
    }
    if (!flags.some((f) => f.k === 'h:' + H.A)) fail('file A was not flagged for the house');

    // 4. Jarvis switches by itself: Best source skips A (flagged) and C (short by length), plays B at the spot.
    await p.waitForFunction(() => /best=1/.test(location.hash) || /#\/play/.test(location.hash) && /GRPB|720p/.test((document.querySelector('#plLine') || {}).textContent || ''), null, { timeout: 30000 });
    await p.waitForFunction(() => { const v = document.querySelector('#vid'); return /#\/play/.test(location.hash) && v && v.readyState >= 1 && v.currentTime > 60; }, null, { timeout: 90000 });
    await p.waitForTimeout(1500);
    const resumed = await play();
    const flags2 = await p.evaluate(async () => RC.badGet('movie:398978'));
    log('interchange resumed', JSON.stringify(resumed), 'flags now', flags2.map((f) => f.k.slice(0, 10)).join(','));
    if (!/720p/.test(resumed.line)) fail(`resumed on "${resumed.line}", expected the healthy 720p file`);
    const off = Math.abs(resumed.t * 1000 - (rec ? rec.pos : CUT_MS));
    log('interchange web to web resume offset ms', Math.round(off));
    if (off > 5000) fail(`resumed ${Math.round(off)} ms away from the saved spot`);
    if (!flags2.some((f) => f.k === 'h:' + H.C)) fail('file C (short by length) was not flagged');
    if (!calls.some((c) => c === 'media /B.mp4')) fail('the healthy file was never loaded');
    if (calls.some((c) => c === 'media /C.mp4')) fail('the short file C was played anyway');

    // 5. The records for the TV side (InterchangeFixtureTest.kt). Times are left out; positions are in ms.
    const fx = {
      note: 'Written by tools/webtest/interchange.mjs (UPDATE_FIXTURE=1). Read by app/src/test/.../InterchangeFixtureTest.kt.',
      title: { type: 'movie', id: TMDB_ID, imdb: IMDB, runtimeMin: RUNTIME_MIN },
      cutAtMs: CUT_MS,
      resume: rec && { type: rec.type, id: rec.id, season: rec.season, episode: rec.episode, pos: Math.round(rec.pos / 1000) * 1000, dur: rec.dur },
      flags: flags2.map((f) => ({ k: f.k, s: Math.round(f.s / 1000) * 1000, n: f.n })).sort((a, b) => a.k.localeCompare(b.k)),
      sources: streams.map((s) => ({ name: s.name, title: s.title, infoHash: s.infoHash, fileIdx: s.fileIdx, rdLengthS: RD_LEN_S[s.title.split('-GRP')[1][0]] })),
      webResumedOnGroup: (/GRP(\w)/.exec(resumed.line) || [, ''])[1] || (/720p/.test(resumed.line) ? 'B' : ''),
    };
    if (process.env.UPDATE_FIXTURE === '1') { fs.writeFileSync(FIXTURE, JSON.stringify(fx, null, 2) + '\n'); log('interchange fixture written'); }
    else {
      const want = JSON.parse(fs.readFileSync(FIXTURE, 'utf8'));
      const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
      if (!same(want.flags.map((f) => [f.k, f.n]), fx.flags.map((f) => [f.k, f.n]))) fail(`flags differ from the fixture: ${JSON.stringify(fx.flags)}`);
      if (!want.resume || !fx.resume || Math.abs(want.resume.pos - fx.resume.pos) > 5000 || want.resume.dur !== fx.resume.dur) fail(`resume record differs from the fixture: ${JSON.stringify(fx.resume)}`);
    }
  } catch (e) {
    fail(String(e).split('\n')[0] + ' | at ' + JSON.stringify(await play().catch(() => ({}))));
  }
  if (errs.length) errs.forEach((e) => fail('pageerror ' + e));
  log('interchange', fails.some((f) => f.startsWith('interchange')) ? 'FAIL' : 'ok');
  await b.close();
}
