// Resume sync for Jarvis: where each profile left off in each movie or show, shared by the house's TVs
// and phones, so playback picks up on any of them (even when the other devices are off).
//
// Everything is encrypted on the device with the house key before it is sent. This function only sees
// opaque tags (HMACs of the house key), timestamps and ciphertext: it can't tell who is watching what.
//
//   GET  /resume?h=<house tag>&p=<profile tag>        -> { e: { <title tag>: { t, v, pp?, pt? } } }
//   POST /resume?h=..&p=..   body { k, t, v, nh? }    -> stores one title (newest t wins)
//   GET  /resume?op=pos&h=..&p=..&k=..&n=..&pos=..    -> a player app (Infuse) reporting where playback stopped:
//        n must match the hash (nh) saved when the title was handed to the player. Saves pos (seconds) as pp.
//
// One small JSON blob per house and profile, at most 300 titles (oldest dropped). Writes use the blob's
// etag, so two devices saving at the same moment can't overwrite each other.
import type { Config } from "@netlify/functions"
import { getStore } from "@netlify/blobs"

const ORIGINS = new Set(["https://jfortress0.github.io"])
const TAG = /^[A-Za-z0-9_-]{16,64}$/
const MAX_ENTRIES = 300
const MAX_V = 4000

type Rec = { t: number; v: string; nh?: string; pp?: number; pt?: number }
type Doc = { e: Record<string, Rec> }

/** Browser requests must come from the web app; the TV app (no Origin header) is allowed too. */
function headersFor(origin: string | null): Record<string, string> | null {
  if (!origin) return {}
  if (!ORIGINS.has(origin)) return null
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "86400",
    Vary: "Origin",
  }
}

const json = (h: Record<string, string>, body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...h, "Content-Type": "application/json", "Cache-Control": "no-store" } })

async function sha256Hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s))
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("")
}

function prune(doc: Doc) {
  const keys = Object.keys(doc.e)
  if (keys.length <= MAX_ENTRIES) return
  keys.sort((a, b) => (doc.e[b].t || 0) - (doc.e[a].t || 0))
  for (const k of keys.slice(MAX_ENTRIES)) delete doc.e[k]
}

/** Read, change, write with the etag (retries when another device wrote in between). */
async function update(key: string, change: (doc: Doc) => boolean): Promise<boolean> {
  const store = getStore({ name: "resume", consistency: "strong" })
  for (let i = 0; i < 5; i++) {
    const cur = await store.getWithMetadata(key, { type: "json" })
    const doc: Doc = cur && cur.data && typeof cur.data === "object" && cur.data.e ? (cur.data as Doc) : { e: {} }
    if (!change(doc)) return true
    prune(doc)
    const res = cur && cur.etag
      ? await store.setJSON(key, doc, { onlyIfMatch: cur.etag })
      : await store.setJSON(key, doc, { onlyIfNew: true })
    if (res.modified) return true
  }
  return false
}

export default async (req: Request) => {
  const h = headersFor(req.headers.get("origin"))
  if (!h) return new Response("Forbidden", { status: 403 })
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: h })

  const u = new URL(req.url)
  const house = u.searchParams.get("h") || ""
  const prof = u.searchParams.get("p") || ""
  if (!TAG.test(house) || !TAG.test(prof)) return json(h, { error: "bad tags" }, 400)
  const key = `${house}/${prof}`
  const op = u.searchParams.get("op") || ""

  try {
    if (req.method === "GET" && !op) {
      const store = getStore({ name: "resume", consistency: "strong" })
      const d = (await store.get(key, { type: "json" })) as Doc | null
      const e: Record<string, Rec> = {}
      // Never send the hand-off hashes back.
      for (const [k, r] of Object.entries((d && d.e) || {})) e[k] = { t: r.t, v: r.v, ...(r.pp != null ? { pp: r.pp, pt: r.pt } : {}) }
      return json(h, { e })
    }

    if (req.method === "GET" && op === "pos") {
      const k = u.searchParams.get("k") || ""
      const n = u.searchParams.get("n") || ""
      const pos = Number(u.searchParams.get("pos"))
      if (!TAG.test(k) || n.length < 16 || n.length > 64 || !isFinite(pos) || pos < 0 || pos > 86400) return json(h, { error: "bad request" }, 400)
      const nh = await sha256Hex(n)
      let found = false
      const ok = await update(key, (doc) => {
        const r = doc.e[k]
        if (!r || r.nh !== nh) return false
        found = true
        const now = Date.now()
        r.pp = Math.round(pos)
        r.pt = now
        r.t = Math.max(r.t || 0, now)
        delete r.nh // one use
        return true
      })
      return json(h, { ok: ok && found }, ok && found ? 200 : 404)
    }

    if (req.method === "POST") {
      const text = await req.text()
      if (text.length > MAX_V + 300) return json(h, { error: "too big" }, 413)
      const b = JSON.parse(text) as { k?: string; t?: number; v?: string; nh?: string }
      const k = String(b.k || "")
      const t = Number(b.t)
      const v = String(b.v || "")
      const nh = b.nh ? String(b.nh) : ""
      if (!TAG.test(k) || !isFinite(t) || t <= 0 || !v || v.length > MAX_V || (nh && !/^[0-9a-f]{64}$/.test(nh))) {
        return json(h, { error: "bad entry" }, 400)
      }
      // Clocks on devices can be off a little; a time far in the future is refused.
      if (t > Date.now() + 10 * 60_000) return json(h, { error: "bad time" }, 400)
      let kept = true
      const ok = await update(key, (doc) => {
        const r = doc.e[k]
        if (r && (r.t || 0) >= t) { kept = false; return false } // a newer save is already there
        doc.e[k] = { t, v, ...(nh ? { nh } : {}) }
        return true
      })
      return json(h, { ok, kept }, ok ? 200 : 409)
    }
  } catch {
    return json(h, { error: "failed" }, 500)
  }
  return json(h, { error: "not allowed" }, 405)
}

export const config: Config = { path: "/resume" }
