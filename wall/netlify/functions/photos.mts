// Jarvis Wall photos, kept in Netlify Blobs (store "wall-photos").
// GET  /api/photos            list (WALL_KEY or UPLOAD_CODE)
// GET  /api/photos/<id>       one JPEG (WALL_KEY or UPLOAD_CODE)
// POST /api/photos            add one JPEG, body = image bytes (UPLOAD_CODE only)
// DELETE /api/photos/<id>     remove one (UPLOAD_CODE only)
// The upload page shrinks every photo to 1920 px JPEG in the phone's browser before sending it.
import type { Config } from "@netlify/functions"
import { getStore } from "@netlify/blobs"

const MAX_BYTES = 5_000_000
const ID = /^[0-9]{13}-[a-z0-9]{6}$/

function timingSafe(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let d = 0
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return d === 0
}

function role(req: Request): "none" | "read" | "write" {
  const got = new URL(req.url).searchParams.get("key") || req.headers.get("x-wall-key") || ""
  const upload = process.env.UPLOAD_CODE || ""
  const read = process.env.WALL_KEY || ""
  if (upload.length >= 8 && timingSafe(upload, got)) return "write"
  if (read.length >= 8 && timingSafe(read, got)) return "read"
  return "none"
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } })

export default async (req: Request) => {
  const r = role(req)
  if (r === "none") return json({ error: "bad_key" }, 401)
  const store = getStore({ name: "wall-photos", consistency: "strong" })
  const id = new URL(req.url).pathname.replace(/^\/api\/photos\/?/, "")

  if (req.method === "GET" && !id) {
    const { blobs } = await store.list()
    const ids = blobs.map((b) => b.key).filter((k) => ID.test(k)).sort().reverse()
    return json({ photos: ids })
  }
  if (id && !ID.test(id)) return json({ error: "bad_id" }, 404)

  if (req.method === "GET") {
    const data = await store.get(id, { type: "arrayBuffer" })
    if (!data) return json({ error: "not_found" }, 404)
    return new Response(data, {
      headers: { "Content-Type": "image/jpeg", "Cache-Control": "private, max-age=31536000, immutable" },
    })
  }

  if (r !== "write") return json({ error: "read_only_key" }, 403)

  if (req.method === "POST" && !id) {
    const buf = await req.arrayBuffer()
    if (buf.byteLength < 1000 || buf.byteLength > MAX_BYTES) return json({ error: "size" }, 413)
    const b = new Uint8Array(buf)
    if (b[0] !== 0xff || b[1] !== 0xd8) return json({ error: "not_jpeg" }, 415)
    const newId = `${Date.now()}-${Math.random().toString(36).slice(2, 8).padEnd(6, "0")}`
    await store.set(newId, buf)
    return json({ id: newId })
  }
  if (req.method === "DELETE" && id) {
    await store.delete(id)
    return json({ ok: true })
  }
  return json({ error: "method" }, 405)
}

export const config: Config = { path: ["/api/photos", "/api/photos/*"] }
