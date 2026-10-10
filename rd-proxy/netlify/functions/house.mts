// House cloud for Jarvis: a few encrypted values per house that every TV keeps current and every phone reads,
// so no single TV has to be on (or reachable) for a phone to have the house's settings and lists.
//
// Everything is encrypted on the device with the house key before it is sent. This function only sees
// opaque tags (HMACs of the house key), timestamps and ciphertext.
//
//   GET  /house?h=<house tag>&k=<value tag>   -> { t, v }   (404 when unknown or expired)
//   POST /house?h=..&k=..   body { t, v, ttl? } -> stores it when t is newer than what is there.
//        ttl (ms, at most 15 minutes): a short-lived value, such as a phone code; read once, then deleted.
import type { Config } from "@netlify/functions"
import { getStore } from "@netlify/blobs"

const ORIGINS = new Set(["https://jfortress0.github.io"])
const TAG = /^[A-Za-z0-9_-]{16,64}$/
const MAX_V = 120_000
const MAX_TTL = 15 * 60_000

type Rec = { t: number; v: string; exp?: number }

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

export default async (req: Request) => {
  const h = headersFor(req.headers.get("origin"))
  if (!h) return new Response("Forbidden", { status: 403 })
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: h })
  const u = new URL(req.url)
  const house = u.searchParams.get("h") || ""
  const k = u.searchParams.get("k") || ""
  if (!TAG.test(house) || !TAG.test(k)) return json(h, { error: "bad tags" }, 400)
  const key = `${house}/${k}`
  const store = getStore({ name: "house", consistency: "strong" })
  try {
    if (req.method === "GET") {
      const d = (await store.get(key, { type: "json" })) as Rec | null
      if (!d || !d.v) return json(h, { error: "none" }, 404)
      if (d.exp) {
        await store.delete(key) // short-lived values are read once
        if (Date.now() > d.exp) return json(h, { error: "expired" }, 404)
      }
      return json(h, { t: d.t, v: d.v })
    }
    if (req.method === "POST") {
      const text = await req.text()
      if (text.length > MAX_V + 300) return json(h, { error: "too big" }, 413)
      const b = JSON.parse(text) as { t?: number; v?: string; ttl?: number }
      const t = Number(b.t)
      const v = String(b.v || "")
      const ttl = b.ttl == null ? 0 : Number(b.ttl)
      if (!isFinite(t) || t <= 0 || !v || v.length > MAX_V || !isFinite(ttl) || ttl < 0 || ttl > MAX_TTL) return json(h, { error: "bad value" }, 400)
      for (let i = 0; i < 5; i++) {
        const cur = await store.getWithMetadata(key, { type: "json" })
        const old = cur && (cur.data as Rec | null)
        if (old && old.t >= t && !old.exp) return json(h, { ok: true, kept: "newer" })
        const rec: Rec = { t, v, ...(ttl ? { exp: Date.now() + ttl } : {}) }
        const res = cur && cur.etag ? await store.setJSON(key, rec, { onlyIfMatch: cur.etag }) : await store.setJSON(key, rec, { onlyIfNew: true })
        if (res.modified) return json(h, { ok: true })
      }
      return json(h, { error: "busy" }, 409)
    }
  } catch {
    return json(h, { error: "failed" }, 500)
  }
  return json(h, { error: "not allowed" }, 405)
}

export const config: Config = { path: "/house" }
