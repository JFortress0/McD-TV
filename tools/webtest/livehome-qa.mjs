// Live TV default view in a real browser: the stand-in playlist (tools/livehome/fixture) served as an Xtream
// account by srv.py (username lh), the page clock set to a guide snapshot, ESPN mocked or empty.
// Checks the rows, the pinned Philadelphia games, the feed list in the player, All Channels A-Z (closed by
// default) and search, then saves screenshots to tools/livehome/qa/ (SHOTS=0 skips them).
// Run: python3 tools/webtest/srv.py & node tools/webtest/livehome-qa.mjs
import { chromium } from 'playwright';
import fs from 'node:fs';

const B = 'http://127.0.0.1:8781/index.html';
const SHOTS = process.env.SHOTS !== '0';
const OUT = new URL('../livehome/qa/', import.meta.url);
if (SHOTS) fs.mkdirSync(OUT, { recursive: true });
const fixture = JSON.parse(fs.readFileSync(new URL('../livehome/fixture/games.json', import.meta.url), 'utf8'));
const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
const fails = [];
const log = (...a) => console.log(...a);
const PATHS = { NFL: '/football/nfl/', NCAAF: '/football/college-football/', NBA: '/basketball/nba/', MLB: '/baseball/mlb/', NHL: '/hockey/nhl/' };

/** ESPN scoreboard JSON for one league from the fixture's games. */
function scoreboard(games, league) {
  const side = (t, ha) => ({ homeAway: ha, score: '0', team: { displayName: t.name, shortDisplayName: t.short, name: t.nick, location: t.loc, abbreviation: t.abbr, logo: '' } });
  return { week: { number: 7 }, events: games.filter((g) => g.league === league).map((g) => ({ id: g.id, date: new Date(g.start).toISOString(),
    status: { type: { state: g.state, shortDetail: g.detail || '' } }, competitions: [{ competitors: [side(g.home, 'home'), side(g.away, 'away')], broadcasts: [{ names: g.casts }] }] })) };
}

async function run(name, { at, games, width = 390, profile = 'p1', fresh = false, shot = '' }) {
  // fresh: a new browser holding only what linking to the TV gives it (the profile and the TV's web_init, with
  // this profile's teams as the TV sends them); otherwise the init has no teams and the defaults apply.
  const ctx = await browser.newContext({ viewport: { width, height: 900 }, serviceWorkers: 'block', timezoneId: 'America/Chicago' });
  const p = await ctx.newPage();
  const errs = [];
  p.on('pageerror', (e) => errs.push(String(e).slice(0, 300)));
  await ctx.route('**/*', (r) => {
    const u = new URL(r.request().url());
    if (u.hostname === '127.0.0.1') return r.continue();
    if (u.hostname === 'site.api.espn.com') {
      const L = Object.keys(PATHS).find((k) => u.pathname.includes(PATHS[k]));
      return r.fulfill({ status: 200, headers: { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json' }, body: JSON.stringify(games && L ? scoreboard(games, L) : { events: [] }) });
    }
    return r.fulfill({ status: 404, headers: { 'Access-Control-Allow-Origin': '*' }, body: '' });
  });
  await p.clock.setFixedTime(new Date(at));
  await p.goto(B);
  await p.evaluate(({ profile, fresh }) => {
    localStorage.clear();
    localStorage.setItem('mcdtv_web_profile', profile);
    const init = { m3u_url: 'http://127.0.0.1:8781/get.php?username=lh&password=p&type=m3u_plus', tmdb_key: 'x' };
    if (fresh) Object.assign(init, { profile, fav_teams: ['NFL:Eagles', 'MLB:Phillies', 'NHL:Flyers', 'NBA:76ers'], live_favorites: [] });
    localStorage.setItem('mcdtv_web_init', JSON.stringify(init));
  }, { profile, fresh });
  await p.goto(B + '#/live'); await p.reload();
  await p.waitForSelector('#lvHome', { timeout: 60000 }).catch(() => fails.push(`${name}: rows never showed`));
  // Guides arrive a few at a time: wait until no guide request is pending and the rows settle.
  await p.waitForFunction(() => !document.querySelector('.lh-empty') || ![...document.querySelectorAll('.lh-empty')].some((e) => /Checking the guide/.test(e.textContent)), null, { timeout: 60000 }).catch(() => {});
  await p.waitForTimeout(2500);
  const rows = await p.$$eval('.lh-row', (rs) => rs.map((r) => ({ id: r.dataset.row, title: r.querySelector('h2').innerText, tiles: [...r.querySelectorAll('.lh-tile')].map((t) => t.innerText.replace(/\s+/g, ' ').trim()), empty: (r.querySelector('.lh-empty') || {}).innerText || '' })));
  const az = await p.$eval('#lvAZ', (d) => ({ open: d.open, summary: d.querySelector('summary').innerText.replace(/\s+/g, ' ') })).catch(() => null);
  log(`\n== ${name} (${width}px, ${new Date(at).toISOString()})`);
  for (const r of rows) { log(`  ${r.title} (${r.tiles.length})${r.empty ? ' [' + r.empty + ']' : ''}`); r.tiles.forEach((t) => log('    - ' + t)); }
  log('  A-Z:', JSON.stringify(az));
  const sw = await p.evaluate(() => document.documentElement.scrollWidth);
  if (width === 390 && sw > 390) fails.push(`${name}: page wider than the phone (${sw})`);
  if (shot && SHOTS) await p.screenshot({ path: new URL(shot, OUT).pathname, fullPage: false });
  const res = { rows, az, p, ctx, errs };
  return res;
}
const ids = (r) => r.rows.map((x) => x.id).join(',');
const ORDER = 'myteams,games,docs,military,tech,prime';

// 1. Sunday 12:40 PM CT, guide only (ESPN empty): the live Eagles game must be pinned from the guide alone.
{
  const r = await run('A: Sunday 12:40 PM CT, guide only', { at: fixture.A.now, games: null, shot: 'web-a-guide-only-390.png' });
  if (ids(r) !== ORDER) fails.push('A: rows ' + ids(r));
  const my = r.rows.find((x) => x.id === 'myteams');
  if (!my || !/^LIVE · ★ MY TEAM/i.test(my.tiles[0] || '') || !/Eagles/.test(my.tiles[0])) fails.push('A: Eagles not pinned live first: ' + (my && my.tiles[0]));
  const lg = r.rows.find((x) => x.id === 'games');
  if (lg.tiles.some((t) => /WNBA|MLS|Soccer|Arsenal|Inter Miami|Alabama/i.test(t))) fails.push('A: Live Games lists an excluded game: ' + lg.tiles.join(' | '));
  if (lg.tiles.filter((t) => /^LIVE/i.test(t)).length !== 4) fails.push('A: expected 4 live games, got ' + lg.tiles.join(' | '));
  if (!r.az || r.az.open) fails.push('A: All Channels A-Z should be closed by default ' + JSON.stringify(r.az));
  // The Eagles tile plays Philadelphia's FOX station and lists the other feeds.
  await r.p.click('.lh-row[data-row="myteams"] .lh-tile');
  await r.p.waitForSelector('#lvFeed', { timeout: 10000 }).catch(() => {});
  const feeds = await r.p.$$eval('#lvFeed option', (o) => o.map((x) => x.textContent + (x.selected ? ' (playing)' : ''))).catch(() => []);
  log('  Eagles feeds in the player:', feeds.join(' | '));
  if (!/WTXF Philadelphia.*\(playing\)/.test(feeds[0] || '')) fails.push('A: the Eagles tile did not open the Philadelphia station first: ' + feeds.join(' | '));
  if (SHOTS) await r.p.screenshot({ path: new URL('web-a-eagles-player-390.png', OUT).pathname });
  await r.p.click('#lvBack');
  // All Channels A-Z opens on tap, lists networks, and search finds a channel deep in a folder.
  await r.p.click('#lvAZ summary'); await r.p.waitForTimeout(800);
  const azRows = await r.p.$$eval('#lvAZ .lh-net', (x) => x.length);
  const chips = await r.p.$$eval('#lvAZ .chip', (x) => x.map((c) => c.innerText.replace(/\s+/g, ' ')));
  log('  A-Z open:', azRows, 'entries shown; kinds:', chips.join(' | '));
  if (!azRows) fails.push('A: A-Z opened with no entries');
  if (SHOTS) { await r.p.evaluate(() => document.querySelector('#lvAZ').scrollIntoView()); await r.p.screenshot({ path: new URL('web-a-az-390.png', OUT).pathname }); }
  for (const q of ['Pluto TV: Westerns', 'ESPN+ 287', 'WTXF', 'UK| BBC One SD', 'Music Choice 42']) {
    await r.p.fill('#lvQ', q); await r.p.waitForTimeout(450);
    const hits = await r.p.$$eval('.lv-row b', (b) => b.map((x) => x.innerText));
    log(`  search "${q}":`, hits.slice(0, 3).join(' | '), hits.length > 3 ? `(+${hits.length - 3})` : '');
    if (!hits.length) fails.push(`A: search "${q}" found nothing`);
  }
  if (r.errs.length) fails.push('A: page errors ' + r.errs.join(' | '));
  await r.ctx.close();
}
// 2. The same moment with ESPN's schedules: next Philadelphia starts in Central Time.
{
  const r = await run('A: Sunday 12:40 PM CT, guide and ESPN', { at: fixture.A.now, games: fixture.A.games, width: 1280, shot: 'web-a-espn-1280.png' });
  const my = r.rows.find((x) => x.id === 'myteams').tiles;
  if (!/Today 4:00 PM CT​? Rangers at Flyers/i.test(my.join(' | '))) fails.push('A+ESPN: Flyers next start missing: ' + my.join(' | '));
  if (r.errs.length) fails.push('A+ESPN: page errors ' + r.errs.join(' | '));
  await r.ctx.close();
}
// 3. Tuesday 7:30 PM CT: Flyers and 76ers live and pinned, primetime on now.
{
  const r = await run('B: Tuesday 7:30 PM CT, guide and ESPN', { at: fixture.B.now, games: fixture.B.games, shot: 'web-b-primetime-390.png' });
  const my = r.rows.find((x) => x.id === 'myteams').tiles;
  if (!(/^LIVE · ★ MY TEAM/.test(my[0]) && /^LIVE · ★ MY TEAM/.test(my[1]))) fails.push('B: two live Philly games not pinned: ' + my.join(' | '));
  const prime = r.rows.find((x) => x.id === 'prime').tiles;
  if (!prime.some((t) => /The Voice/.test(t))) fails.push('B: primetime row without The Voice: ' + prime.slice(0, 5).join(' | '));
  if (r.errs.length) fails.push('B: page errors ' + r.errs.join(' | '));
  await r.ctx.close();
}
// 4. Fresh profile: a new browser with only what linking gives it: no guide cache, favorites or recents.
{
  const r = await run('Fresh browser profile, Sunday 12:40 PM CT', { at: fixture.A.now, games: fixture.A.games, fresh: true, shot: 'web-fresh-390.png' });
  if (ids(r) !== ORDER) fails.push('fresh: rows ' + ids(r));
  if (!/Eagles/.test(r.rows[0].tiles[0] || '')) fails.push('fresh: My Teams without the Eagles: ' + r.rows[0].tiles.join(' | '));
  if (!r.az || r.az.open) fails.push('fresh: A-Z not collapsed');
  if (r.errs.length) fails.push('fresh: page errors ' + r.errs.join(' | '));
  await r.ctx.close();
}
// 5. Another profile with no teams: the same rows, My Teams says how to add teams.
{
  const r = await run('Profile 2 (no teams), Sunday 12:40 PM CT', { at: fixture.A.now, games: fixture.A.games, profile: 'p2' });
  if (ids(r) !== ORDER) fails.push('p2: rows ' + ids(r));
  if (r.errs.length) fails.push('p2: page errors ' + r.errs.join(' | '));
  await r.ctx.close();
}
await browser.close();
console.log(fails.length ? '\nFAILS ' + fails.length + '\n' + fails.join('\n') : '\nlivehome qa ok');
process.exit(fails.length ? 1 : 0);
