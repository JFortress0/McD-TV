// Short invite links for the Jarvis web app. A full setup (TV link, sources, playlist, keys) is too long to text,
// so the web app encrypts it on the phone with a random key, stores the ciphertext here, and texts a short link:
// .../app/#j.<id>.<key>. The key stays in the link (after "#", never sent to a server); this function only sees
// the id and ciphertext. Invites expire after 7 days.
//
//   POST /invite   body { id, v }   -> stores it
//   GET  /invite?id=<id>            -> { v }  (404 when unknown or expired)
import type { Config } from "@netlify/functions"
import { getStore } from "@netlify/blobs"

const ORIGINS = new Set(["https://jfortress0.github.io"])
const ID = /^[A-Za-z0-9_-]{16,40}$/
const MAX_V = 60_000
const TTL_MS = 7 * 24 * 3600_000

function headersFor(origin: string | null): Record<string, string> | null {
  if (!origin || !ORIGINS.has(origin)) return null
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
  const store = getStore({ name: "invites", consistency: "strong" })
  try {
    if (req.method === "POST") {
      const text = await req.text()
      if (text.length > MAX_V + 200) return json(h, { error: "too big" }, 413)
      const b = JSON.parse(text) as { id?: string; v?: string }
      const id = String(b.id || "")
      const v = String(b.v || "")
      if (!ID.test(id) || !v || v.length > MAX_V) return json(h, { error: "bad invite" }, 400)
      const res = await store.setJSON(id, { v, at: Date.now() }, { onlyIfNew: true })
      return json(h, { ok: res.modified }, res.modified ? 200 : 409)
    }
    if (req.method === "GET") {
      const id = new URL(req.url).searchParams.get("id") || ""
      if (!ID.test(id)) return json(h, { error: "bad id" }, 400)
      const d = (await store.get(id, { type: "json" })) as { v?: string; at?: number } | null
      if (!d || !d.v || Date.now() - (d.at || 0) > TTL_MS) {
        if (d) await store.delete(id)
        return json(h, { error: "expired" }, 404)
      }
      return json(h, { v: d.v })
    }
  } catch {
    return json(h, { error: "failed" }, 500)
  }
  return json(h, { error: "not allowed" }, 405)
}

export const config: Config = { path: "/invite" }
