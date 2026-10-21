// Unit tests of the web player's quality fallback (QF in docs/app/index.html). No browser needed.
// Every case in tools/qf-vectors.json (the TV's QualityFallbackTest runs the same file), then web-only helpers.
// Run: node tools/webtest/qf-test.mjs
import fs from 'node:fs';
const html = fs.readFileSync(new URL('../../docs/app/index.html', import.meta.url), 'utf8');
const V = JSON.parse(fs.readFileSync(new URL('../qf-vectors.json', import.meta.url), 'utf8'));
const m = /\/\/ >>> QualityFallback[^\n]*\n([\s\S]*?)\/\/ <<< QualityFallback/.exec(html);
if (!m) { console.log('QF block not found'); process.exit(1); }
const QF = new Function(m[1] + '\nreturn QF;')();
const fails = [];
const eq = (name, got, want) => { if (JSON.stringify(got) !== JSON.stringify(want)) fails.push(`${name}: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`); };

// The constants match the shared file.
for (const [k, v] of Object.entries(V.constants)) eq('constant ' + k, QF.T[k], v);
let n = 0;
for (const c of V.cases) {
  n++;
  if (c.kind === 'monitor') {
    const q = QF.monitor(c.t0);
    c.ops.forEach((op, i) => {
      const [f, ...a] = op;
      if (f === 'check') eq(`${c.name} [op ${i} check ${a[0]}]`, q.check(a[0]), a[1]);
      else q[f](...a);
    });
  } else if (c.kind === 'estimator') {
    const e = QF.estimator(); c.adds.forEach(([b, ms]) => e.add(b, ms));
    const got = Math.round(e.estimate()); if (Math.abs(got - c.expect) > 1) eq(c.name, got, c.expect);
  } else if (c.kind === 'startTier') eq(c.name, QF.startTier(c.in), c.out);
  else fails.push('unknown case kind ' + c.kind);
}

// Names and heights.
eq('tiers', ['ESPN 4K', 'ESPN UHD', 'FOX 2160p', 'ESPN FHD', 'ESPN 1080p', 'ESPN HD', 'ESPN SD', 'ESPN 720p', 'ESPN', 'HDTV Guide'].map(QF.tierOf), [4, 4, 4, 3, 3, 2, 1, 1, 0, 0]);
eq('is4k by height', QF.is4k('ESPN', 2160), true);
eq('is4k by name', QF.is4k('4K', 0), true);
eq('1080p is not 4k', QF.is4k('1080p', 1080), false);
// Every reason code has words for the log.
for (const w of ['long_stall', 'stalls', 'stall_time', 'very_slow', 'slow', 'slow_network', 'froze_before', 'fast_again']) if (!QF.WHY[w]) fails.push('no words for ' + w);

// Live sibling: same key, never 4K, 1080p over HD over unlabeled over SD, the playing one skipped.
const key = (s) => s.toLowerCase().replace(/\b(?:4k|uhd|fhd|hd|sd|1080p|720p)\b/g, '').replace(/[^a-z0-9]+/g, ' ').trim();
const L = [{ raw: 'ESPN 4K', url: 'a' }, { raw: 'ESPN SD', url: 'b' }, { raw: 'ESPN', url: 'c' }, { raw: 'ESPN HD', url: 'd' }, { raw: 'ESPN2 FHD', url: 'e' }, { raw: 'ESPN UHD', url: 'f' }];
eq('picks HD when no 1080p', QF.liveSibling(L, L[0], key), 3);
eq('picks 1080p first', QF.liveSibling(L.concat([{ raw: 'ESPN FHD', url: 'g' }]), L[0], key), 6);
eq('ok filter', QF.liveSibling(L, L[0], key, (c) => c.url !== 'd'), 2);
eq('none', QF.liveSibling([{ raw: 'FOX 4K', url: 'x' }, { raw: 'FOX UHD', url: 'y' }], { raw: 'FOX 4K', url: 'x' }, key), -1);

// hls.js levels: the highest at or under 1080 lines, only when a 4K level exists.
eq('cap level', QF.capLevel([{ height: 720 }, { height: 1080 }, { height: 2160 }]), 1);
eq('no 4K level', QF.capLevel([{ height: 720 }, { height: 1080 }]), -1);
eq('single level', QF.capLevel([{ height: 2160 }]), -1);

// Probe helpers: the second newest segment, absolute; a master playlist is left to the player.
eq('media playlist', QF.parsePlaylist('#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\nseg1.m4s\n#EXTINF:2.002,\nseg2.m4s\n#EXTINF:2.0,\nseg3.m4s\n', 'http://h/live/a/index.m3u8'),
  { master: false, seg: { url: 'http://h/live/a/seg2.m4s', dur: 2.002 } });
eq('master playlist', QF.parsePlaylist('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=6000000\nhi.m3u8\n', 'http://h/'), { master: true });
eq('not a playlist', QF.parsePlaylist('<html>', 'http://h/'), null);
eq('probe 1.5 MB in 2 s, 2 s segment', QF.probeResult(1.5e6, 2000, 2, true), { est: 6e6, br: 6e6 });
eq('probe cut short: speed only', QF.probeResult(0.75e6, 2500, 2, false), { est: 2.4e6, br: 0 });

console.log(fails.length ? 'FAIL\n - ' + fails.join('\n - ') : `qf-test: all passed (${n} shared cases)`);
process.exit(fails.length ? 1 : 0);
