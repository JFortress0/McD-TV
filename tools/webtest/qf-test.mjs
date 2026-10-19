// Unit tests of the web player's quality fallback (QF in docs/app/index.html). No browser needed.
// Run: node tools/webtest/qf-test.mjs
import fs from 'node:fs';
const html = fs.readFileSync(new URL('../../docs/app/index.html', import.meta.url), 'utf8');
const m = /\/\/ >>> QualityFallback[^\n]*\n([\s\S]*?)\/\/ <<< QualityFallback/.exec(html);
if (!m) { console.log('QF block not found'); process.exit(1); }
const QF = new Function(m[1] + '\nreturn QF;')();
const fails = [];
const eq = (name, got, want) => { if (JSON.stringify(got) !== JSON.stringify(want)) fails.push(`${name}: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`); };

// Stalls: 3 within 60 s fire; the first 10 s are ignored.
{ const q = QF.monitor(0);
  q.stall(2000); q.resume(4000); q.stall(5000); q.resume(6000); // startup: ignored
  eq('startup stalls ignored', q.check(6000), '');
  q.stall(20000); q.resume(21000); q.stall(30000); q.resume(31000);
  eq('two stalls fine', q.check(31000), '');
  q.stall(40000); q.resume(41000);
  eq('three stalls in 60 s', q.check(41000), 'stalls');
  eq('fires once', q.check(42000), '');
  q.restart(50000); eq('restart clears', q.check(50000), ''); }
// Stalls spread out over more than 60 s do not fire.
{ const q = QF.monitor(0);
  q.stall(15000); q.resume(16000); q.stall(50000); q.resume(51000); q.stall(80000); q.resume(81000);
  eq('stalls 65 s apart', q.check(81000), ''); }
// One long stall: 8 s.
{ const q = QF.monitor(0);
  q.stall(20000); eq('7 s stall', q.check(27000), ''); eq('8 s stall', q.check(28000), 'long stall'); }
// Total stalled time: 12 s within 60 s, two stalls of 7 s and 5 s.
{ const q = QF.monitor(0);
  q.stall(20000); q.resume(27000); eq('7 s total', q.check(27000), '');
  q.stall(40000); q.resume(45000); eq('12 s total', q.check(45000), 'stall time'); }
// A seek's buffering is not a stall.
{ const q = QF.monitor(0);
  q.stall(20000); q.cancel(); q.stall(30000); q.cancel(); q.stall(35000); q.cancel();
  eq('seek buffering ignored', q.check(60000), ''); }
// Throughput: 4 low samples in a row, 5 s apart. Samples closer than 5 s are skipped; a good one resets.
{ const q = QF.monitor(0);
  q.sample(10000, 8e6, 20e6); q.sample(12000, 8e6, 20e6); q.sample(15000, 8e6, 20e6); q.sample(20000, 8e6, 20e6);
  eq('3 low samples', q.check(20000), '');
  q.sample(25000, 30e6, 20e6); q.sample(30000, 8e6, 20e6);
  eq('reset by a good sample', q.check(30000), '');
  q.sample(35000, 8e6, 20e6); q.sample(40000, 8e6, 20e6); q.sample(45000, 8e6, 20e6);
  eq('4 low samples in a row', q.check(45000), 'slow network'); }
// Unknown bitrate never counts.
{ const q = QF.monitor(0);
  for (let t = 10000; t <= 60000; t += 5000) q.sample(t, 1e6, 0);
  eq('unknown bitrate', q.check(60000), ''); }

// Names and heights.
eq('tiers', ['ESPN 4K', 'ESPN UHD', 'FOX 2160p', 'ESPN FHD', 'ESPN 1080p', 'ESPN HD', 'ESPN SD', 'ESPN 720p', 'ESPN', 'HDTV Guide'].map(QF.tierOf), [4, 4, 4, 3, 3, 2, 1, 1, 0, 0]);
eq('is4k by height', QF.is4k('ESPN', 2160), true);
eq('is4k by name', QF.is4k('4K', 0), true);
eq('1080p is not 4k', QF.is4k('1080p', 1080), false);

// Live sibling: same key, never 4K, 1080p over HD over unlabeled over SD, the playing one skipped.
const key = (n) => n.toLowerCase().replace(/\b(?:4k|uhd|fhd|hd|sd|1080p|720p)\b/g, '').replace(/[^a-z0-9]+/g, ' ').trim();
const L = [{ raw: 'ESPN 4K', url: 'a' }, { raw: 'ESPN SD', url: 'b' }, { raw: 'ESPN', url: 'c' }, { raw: 'ESPN HD', url: 'd' }, { raw: 'ESPN2 FHD', url: 'e' }, { raw: 'ESPN UHD', url: 'f' }];
eq('picks HD when no 1080p', QF.liveSibling(L, L[0], key), 3);
eq('picks 1080p first', QF.liveSibling(L.concat([{ raw: 'ESPN FHD', url: 'g' }]), L[0], key), 6);
eq('ok filter', QF.liveSibling(L, L[0], key, (c) => c.url !== 'd'), 2);
eq('none', QF.liveSibling([{ raw: 'FOX 4K', url: 'x' }, { raw: 'FOX UHD', url: 'y' }], { raw: 'FOX 4K', url: 'x' }, key), -1);

// hls.js levels: the highest at or under 1080 lines, only when a 4K level exists.
eq('cap level', QF.capLevel([{ height: 720 }, { height: 1080 }, { height: 2160 }]), 1);
eq('no 4K level', QF.capLevel([{ height: 720 }, { height: 1080 }]), -1);
eq('single level', QF.capLevel([{ height: 2160 }]), -1);

console.log(fails.length ? 'FAIL\n - ' + fails.join('\n - ') : 'qf-test: all passed');
process.exit(fails.length ? 1 : 0);
