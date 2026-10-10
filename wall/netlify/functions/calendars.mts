// Jarvis Wall calendar list, edited from the phone page (UPLOAD_CODE only).
// GET  /api/calendars  names, tags, colors, kinds and the link's host. The links themselves are never sent back.
// PUT  /api/calendars  replace the list. An entry with a blank url keeps the saved link of the same id.
// Saved in Netlify Blobs (store "wall-config", key "calendars"). The feed prefers this over WALL_CALENDARS.
import type { Config } from "@netlify/functions"
import { getStore } from "@netlify/blobs"

type Cal = { id: string; name: string; tag: string; color: string; kind: string; url: string }

function timingSafe(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let d = 0
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return d === 0
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } })

function host(u: string): string {
  try { return new URL(u.replace(/^webcal:/i, "https:")).host } catch { return "" }
}

export default async (req: Request) => {
  const code = process.env.UPLOAD_CODE || ""
  const got = req.headers.get("x-wall-key") || ""
  if (code.length < 8 || !timingSafe(code, got)) return json({ error: "bad_key" }, 401)
  const store = getStore({ name: "wall-config", consistency: "strong" })
  const saved = ((await store.get("calendars", { type: "json" })) as Cal[] | null) || []

  if (req.method === "GET") {
    return json({ calendars: saved.map(({ url, ...c }) => ({ ...c, host: host(url) })) })
  }
  if (req.method === "PUT") {
    let body: unknown
    try { body = await req.json() } catch { return json({ error: "bad_json" }, 400) }
    if (!Array.isArray(body) || body.length > 12) return json({ error: "bad_list" }, 400)
    const out: Cal[] = []
    for (const c of body) {
      const id = typeof c?.id === "string" && /^[a-z0-9]{6,16}$/.test(c.id) ? c.id : Math.random().toString(36).slice(2, 10)
      let url = typeof c?.url === "string" ? c.url.trim() : ""
      if (!url) url = saved.find((s) => s.id === id)?.url || ""
      if (!/^(https?|webcal):\/\/\S+$/i.test(url)) return json({ error: "bad_url", name: String(c?.name || "") }, 400)
      out.push({
        id,
        name: String(c.name || "Calendar").slice(0, 30),
        tag: String(c.tag || c.name || "").slice(0, 8),
        color: /^#[0-9a-f]{6}$/i.test(c.color) ? c.color : "#00E5FF",
        kind: ["work", "personal", "school", "family"].includes(c.kind) ? c.kind : "personal",
        url,
      })
    }
    await store.setJSON("calendars", out)
    return json({ ok: true, count: out.length })
  }
  return json({ error: "method" }, 405)
}

export const config: Config = { path: "/api/calendars" }
