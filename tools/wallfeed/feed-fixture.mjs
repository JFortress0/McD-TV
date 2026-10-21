// Runs the real Wall feed code (wall/netlify/functions/feed.mts) over the iCal fixtures in this folder and
// checks the recurrence flag (r: 1) on every event. Writes spotlight-feed.json, which the Kotlin test
// WallSpotlightTest reads, so the TV logic is tested on what the feed really sends.
//
// Usage (Node 22.18+ for .mts type stripping; needs `npm install` in wall/):
//   node tools/wallfeed/feed-fixture.mjs           check, and fail if spotlight-feed.json is out of date
//   node tools/wallfeed/feed-fixture.mjs --write   check, and rewrite spotlight-feed.json
import { readFileSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import assert from "node:assert/strict"

const here = dirname(fileURLToPath(import.meta.url))
const { expand } = await import(join(here, "../../wall/netlify/functions/feed.mts"))

// The fixture clock: Wednesday, October 21, 2026, noon in Huntsville (America/Chicago, CDT).
const NOW = Date.parse("2026-10-21T12:00:00-05:00")
const DAY_MS = 86_400_000
const from = new Date(NOW - 40 * DAY_MS)
const to = new Date(NOW + 70 * DAY_MS)

const sources = [
  { name: "Family", tag: "Fam", color: "#00E5FF", kind: "family", file: "family.ics" },
  { name: "Partner", tag: "Partner", color: "#FF7AB6", kind: "work", file: "partner.ics" },
  { name: "School", tag: "SNHU", color: "#FFB300", kind: "school", file: "school.ics" },
]
const events = sources.flatMap((s, i) => expand(readFileSync(join(here, s.file), "utf8"), i, from, to))
events.sort((a, b) => (a.start ?? Date.parse(a.day)) - (b.start ?? Date.parse(b.day)) || a.t.localeCompare(b.t))

let checks = 0
const ok = (cond, msg) => { assert.ok(cond, msg); checks++; console.log("  ok  " + msg) }
const named = (t) => events.filter((e) => e.t === t)

const weekly = named("Weekly game")
ok(weekly.length >= 8 && weekly.every((e) => e.r === 1), `weekly timed series: ${weekly.length} occurrences, all r: 1`)
const moved = named("Weekly game (moved to Friday)")
ok(moved.length === 1 && moved[0].r === 1, "moved repeat of the weekly series keeps r: 1")
const bump = named("Weekly bump photo")
ok(bump.length >= 8 && bump.every((e) => e.r === 1), `weekly all-day series: ${bump.length} occurrences, all r: 1`)
const rdate = named("Review on set dates")
// ical.js skips the DTSTART of an RDATE-only series (older feed behavior, not changed here); the extra dates come through.
ok(rdate.length >= 2 && rdate.every((e) => e.r === 1), `RDATE series: ${rdate.length} dates, all r: 1`)
const orphan = named("Moved repeat without its series")
ok(orphan.length === 1 && orphan[0].r === 1, "moved repeat with no series in the file: r: 1")
for (const t of ["Beach trip", "Beach Trip", "Dentist", "Haircut today", "Six weeks out", "Concert", "Fourth one-off",
  "Module 5 assignment due", "Module 6 assignment due"]) {
  const e = named(t)
  ok(e.length === 1 && e[0].r === undefined, `one-off "${t}": one event, no r`)
}
ok(named("Cancelled party").length === 0, "cancelled one-off is dropped")
const trip = named("Beach trip")[0]
ok(trip.day === "2026-11-11" && trip.endDay === "2026-11-14" && trip.loc === "Gulf Shores, AL", "trip keeps its full range and location")

const body = JSON.stringify({
  v: 2,
  generated: NOW,
  tz: "America/Chicago",
  sources: sources.map(({ name, tag, color, kind }) => ({ name, tag, color, kind, error: null })),
  events,
  weather: null,
}, null, 1) + "\n"
const out = join(here, "spotlight-feed.json")
if (process.argv.includes("--write")) {
  writeFileSync(out, body)
  console.log(`wrote ${out}`)
} else {
  ok(readFileSync(out, "utf8") === body, "spotlight-feed.json matches the feed output")
}
console.log(`PASS: ${checks} checks`)
