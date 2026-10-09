// Real-Debrid pass-through for the Jarvis web app (docs/app).
// Browsers can't call api.real-debrid.com: it sends no CORS headers, so every reply is blocked.
// This function forwards the web app's own requests and adds CORS headers for the web app's origin only.
// It stores nothing and logs nothing. Each phone signs in to Real-Debrid itself; its token passes through.
import type { Config } from "@netlify/functions"

const RD = "https://api.real-debrid.com/"
const ORIGINS = new Set(["https://jfortress0.github.io"])

// Only the calls the web app makes (method + path pattern under /rd/).
const ALLOWED: Array<[string, RegExp]> = [
  ["GET", /^oauth\/v2\/device\/code$/],
  ["GET", /^oauth\/v2\/device\/credentials$/],
  ["POST", /^oauth\/v2\/token$/],
  ["GET", /^rest\/1\.0\/user$/],
  ["GET", /^rest\/1\.0\/torrents$/],
  ["GET", /^rest\/1\.0\/torrents\/info\/[A-Za-z0-9]+$/],
  ["POST", /^rest\/1\.0\/torrents\/addMagnet$/],
  ["POST", /^rest\/1\.0\/torrents\/selectFiles\/[A-Za-z0-9]+$/],
  ["DELETE", /^rest\/1\.0\/torrents\/delete\/[A-Za-z0-9]+$/],
  ["POST", /^rest\/1\.0\/unrestrict\/link$/],
  ["GET", /^rest\/1\.0\/streaming\/transcode\/[A-Za-z0-9]+$/],
]

function cors(origin: string | null): Record<string, string> {
  if (!origin || !ORIGINS.has(origin)) return {}
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Max-Age": "86400",
    Vary: "Origin",
  }
}

export default async (req: Request) => {
  const origin = req.headers.get("origin")
  const h = cors(origin)
  if (!h["Access-Control-Allow-Origin"]) return new Response("Forbidden", { status: 403 })
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: h })

  const url = new URL(req.url)
  const path = url.pathname.replace(/^\/rd\//, "")
  if (!ALLOWED.some(([m, re]) => m === req.method && re.test(path))) {
    return new Response(JSON.stringify({ error: "not_allowed" }), { status: 404, headers: { ...h, "Content-Type": "application/json" } })
  }

  const headers: Record<string, string> = { "User-Agent": "Jarvis-web" }
  const auth = req.headers.get("authorization")
  if (auth) headers.Authorization = auth
  let body: string | undefined
  if (req.method === "POST") {
    body = await req.text()
    if (body.length > 20_000) return new Response("Too large", { status: 413, headers: h })
    headers["Content-Type"] = "application/x-www-form-urlencoded"
  }

  let r: Response
  try {
    r = await fetch(RD + path + url.search, { method: req.method, headers, body, redirect: "manual", signal: AbortSignal.timeout(25_000) })
  } catch {
    return new Response(JSON.stringify({ error: "real_debrid_unreachable" }), { status: 502, headers: { ...h, "Content-Type": "application/json" } })
  }
  const out = await r.arrayBuffer()
  return new Response(r.status === 204 ? null : out, {
    status: r.status,
    headers: { ...h, "Content-Type": r.headers.get("content-type") || "application/json", "Cache-Control": "no-store" },
  })
}

export const config: Config = { path: "/rd/*" }
