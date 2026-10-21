// Live TV audit: what the old organizer (PR #7) showed and what the new default view shows, for one playlist.
// Prints counts and network names only, never a link or a login.
//   node tools/livehome/audit.mjs                         the stand-in fixture, snapshot A (Sunday 12:40 PM CT)
//   node tools/livehome/audit.mjs playlist.m3u guide.xml  your own files (download them yourself; plain or .gz)
//   AT=2026-10-27T19:30:00-05:00 node tools/livehome/audit.mjs ...   another moment
//   GAMES=A|B  use the fixture's ESPN-style games for that snapshot (fixture only)
import fs from 'node:fs';
import { loadEngine, readText, parseM3u, parseXmltv, prepare } from './load.mjs';

const fx = (f) => new URL('./fixture/' + f, import.meta.url);
const [m3uPath, xmlPath] = process.argv.slice(2);
const E = loadEngine();
const raw = parseM3u(readText(m3uPath || fx('playlist.m3u.gz')));
const guide = parseXmltv(xmlPath || !m3uPath ? readText(xmlPath || fx('guide.xml.gz')) : '');
const now = Date.parse(process.env.AT || '2026-10-25T12:40:00-05:00');
const games = process.env.GAMES && !m3uPath ? JSON.parse(fs.readFileSync(fx('games.json'), 'utf8'))[process.env.GAMES].games : [];
const teams = [{ league: 'NFL', name: 'Eagles' }, { league: 'MLB', name: 'Phillies' }, { league: 'NHL', name: 'Flyers' }, { league: 'NBA', name: '76ers' }];

const D = prepare(E, raw, guide);
const before = {};
D.section.forEach((s) => { before[s || '(no section)'] = (before[s || '(no section)'] || 0) + 1; });
const idx = E.LH.groups(D.chs, (i) => D.country[i], (i) => D.section[i]);
const ev = (i) => E.P.eventProg(D.chs[i].name, D.chs[i].group, now);
const H = E.LH.home({ idx, chs: D.chs, progs: D.progs, games, teams, now, eventOf: ev });

console.log(`Playlist: ${raw.length} entries, ${D.chs.length} channels after separators and repeated links.`);
console.log(`Guide: ${guide.size} guide channels; ${D.gid.filter(Boolean).length} playlist channels matched to a guide.`);
console.log(`\nBEFORE (web chips and TV rail count channels):`);
for (const [k, v] of Object.entries(before).sort((a, b) => b[1] - a[1])) console.log(`  ${k.padEnd(16)} ${v}`);
console.log(`\nAFTER, default view at ${new Date(now).toISOString()}:`);
for (const r of H.rows) {
  console.log(`  ${r.title} (${r.tiles.length})`);
  for (const t of r.tiles) console.log(`    - ${t.title} | ${t.sub} | ${t.badge} | ${t.feeds.length} feed${t.feeds.length === 1 ? '' : 's'}`);
}
const kinds = {};
for (const g of idx.list) kinds[g.kind] = (kinds[g.kind] || 0) + 1;
console.log(`\nAll Channels A-Z (collapsed): ${idx.list.length} entries for ${D.chs.length} channels.`);
console.log('  by kind: ' + Object.entries(kinds).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k} ${v}`).join(', '));
const placed = new Set(); let dup = 0;
for (const g of idx.list) for (const i of g.feeds) { if (placed.has(i)) dup++; placed.add(i); }
console.log(`  every channel placed once: ${placed.size === D.chs.length && !dup ? 'yes' : `NO (${placed.size} placed, ${dup} twice)`}`);
