// Jarvis Wall: one merged calendar feed for the wall TV.
// The calendar links (secret iCal addresses) live only in the Netlify environment variable WALL_CALENDARS.
// They never reach the TV, the repo or the browser. The TV gets plain JSON: titles, times and colors.
import type { Config } from "@netlify/functions"
import ICAL from "ical.js"

type Source = { name: string; tag: string; color: string; kind: string; url: string }
type Out = { s: number; t: string; start?: number; end?: number; day?: string; endDay?: string; loc?: string }

const DEFAULT_COLORS = ["#00E5FF", "#7C9CFF", "#4ADE80", "#FF7AB6", "#FFB300", "#C084FC"]
const DAY_MS = 86_400_000
let cache: { at: number; body: string } | null = null

function sources(): Source[] {
  let raw: unknown
  try { raw = JSON.parse(process.env.WALL_CALENDARS || "[]") } catch { return [] }
  if (!Array.isArray(raw)) return []
  return raw
    .filter((c) => c && typeof c.url === "string" && /^(https?|webcal):\/\//i.test(c.url))
    .map((c, i) => ({
      name: String(c.name || `Calendar ${i + 1}`).slice(0, 30),
      tag: String(c.tag || c.name || "").slice(0, 8),
      color: /^#[0-9a-f]{6}$/i.test(c.color) ? c.color : DEFAULT_COLORS[i % DEFAULT_COLORS.length],
      kind: ["work", "personal", "school", "family"].includes(c.kind) ? c.kind : "personal",
      url: String(c.url).replace(/^webcal:/i, "https:"),
    }))
}

function keyOk(req: Request): boolean {
  const want = [process.env.WALL_KEY, process.env.UPLOAD_CODE].filter((k): k is string => !!k && k.length >= 8)
  const got = new URL(req.url).searchParams.get("key") || req.headers.get("x-wall-key") || ""
  return want.some((k) => k.length === got.length && timingSafe(k, got))
}

function timingSafe(a: string, b: string): boolean {
  let d = 0
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return d === 0
}

/** One calendar file to events inside [from, to). Expands repeats, applies moved and cancelled repeats. */
export function expand(text: string, si: number, from: Date, to: Date): Out[] {
  const root = new ICAL.Component(ICAL.parse(text))
  for (const tz of root.getAllSubcomponents("vtimezone")) {
    try { ICAL.TimezoneService.register(tz) } catch { /* duplicate or bad zone: the event falls back to UTC */ }
  }
  const masters = new Map<string, ICAL.Event>()
  const loose: ICAL.Event[] = []
  const exceptions: ICAL.Event[] = []
  for (const v of root.getAllSubcomponents("vevent")) {
    const e = new ICAL.Event(v)
    if (e.isRecurrenceException()) exceptions.push(e)
    else if (e.uid && !masters.has(e.uid)) masters.set(e.uid, e)
    else loose.push(e)
  }
  for (const ex of exceptions) {
    const m = masters.get(ex.uid)
    if (m) m.relateException(ex)
    else loose.push(ex)
  }

  const out: Out[] = []
  const push = (item: ICAL.Event, s: ICAL.Time, e: ICAL.Time | null) => {
    if (String(item.component.getFirstPropertyValue("status") || "").toUpperCase() === "CANCELLED") return
    const title = (item.summary || "Busy").trim().slice(0, 120)
    const loc = (item.location || "").trim().slice(0, 80) || undefined
    if (s.isDate) {
      // DTEND of an all-day event is the day after the last day.
      let end = e
      if (!end || end.compare(s) <= 0) { end = s.clone(); end.adjust(1, 0, 0, 0) }
      const endDay = end.clone()
      endDay.adjust(-1, 0, 0, 0)
      const sd = s.toString().slice(0, 10)
      const ed = endDay.toString().slice(0, 10)
      if (Date.parse(ed + "T23:59:59Z") < from.getTime() - DAY_MS || Date.parse(sd) > to.getTime()) return
      out.push({ s: si, t: title, day: sd, endDay: ed === sd ? undefined : ed, loc })
    } else {
      const start = s.toJSDate().getTime()
      const end = e ? e.toJSDate().getTime() : start
      if (end < from.getTime() || start >= to.getTime()) return
      out.push({ s: si, t: title, start, end: Math.max(end, start), loc })
    }
  }

  for (const e of [...masters.values(), ...loose]) {
    if (!e.startDate) continue
    if (!e.isRecurring() || e.isRecurrenceException()) {
      push(e, e.startDate, e.endDate)
      continue
    }
    const it = e.iterator()
    let guard = 0
    for (let next = it.next(); next && guard < 50_000; next = it.next(), guard++) {
      if (next.toJSDate().getTime() >= to.getTime()) break
      const d = e.getOccurrenceDetails(next)
      if (d.endDate.toJSDate().getTime() < from.getTime() - DAY_MS) continue
      push(d.item, d.startDate, d.endDate)
    }
  }
  return out
}

async function weather() {
  const lat = Number(process.env.WALL_LAT || "34.73")
  const lon = Number(process.env.WALL_LON || "-86.59")
  const tz = process.env.WALL_TZ || "America/Chicago"
  const u = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
    `&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max` +
    `&temperature_unit=fahrenheit&timezone=${encodeURIComponent(tz)}&forecast_days=5`
  try {
    const j = await (await fetch(u, { signal: AbortSignal.timeout(8000) })).json()
    return {
      now: Math.round(j.current.temperature_2m),
      code: j.current.weather_code,
      days: (j.daily.time as string[]).map((d, i) => ({
        day: d,
        hi: Math.round(j.daily.temperature_2m_max[i]),
        lo: Math.round(j.daily.temperature_2m_min[i]),
        code: j.daily.weather_code[i],
        rain: j.daily.precipitation_probability_max?.[i] ?? null,
      })),
    }
  } catch {
    return null
  }
}

export default async (req: Request) => {
  const json = { "Content-Type": "application/json", "Cache-Control": "no-store" }
  if (!keyOk(req)) return new Response(JSON.stringify({ error: "bad_key" }), { status: 401, headers: json })
  if (cache && Date.now() - cache.at < 120_000) return new Response(cache.body, { headers: json })

  const list = sources()
  const now = Date.now()
  const from = new Date(now - 40 * DAY_MS) // covers the whole month view
  const to = new Date(now + 70 * DAY_MS)
  const results = await Promise.all(list.map(async (src, i) => {
    try {
      const r = await fetch(src.url, { signal: AbortSignal.timeout(15_000), headers: { "User-Agent": "Jarvis-Wall" } })
      if (!r.ok) throw new Error(`HTTP ${r.status}`)
      return { events: expand(await r.text(), i, from, to), error: null as string | null }
    } catch (e) {
      return { events: [] as Out[], error: e instanceof Error ? e.message.slice(0, 80) : "failed" }
    }
  }))
  const events = results.flatMap((r) => r.events)
  events.sort((a, b) => (a.start ?? Date.parse(a.day!)) - (b.start ?? Date.parse(b.day!)))
  const body = JSON.stringify({
    v: 1,
    generated: now,
    tz: process.env.WALL_TZ || "America/Chicago",
    sources: list.map((s, i) => ({ name: s.name, tag: s.tag, color: s.color, kind: s.kind, error: results[i].error })),
    events,
    weather: await weather(),
  })
  cache = { at: Date.now(), body }
  return new Response(body, { headers: json })
}

export const config: Config = { path: "/api/feed" }
