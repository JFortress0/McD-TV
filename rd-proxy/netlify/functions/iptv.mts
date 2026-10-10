// Channel list for the Jarvis web app's Live TV (docs/app), the way the TV app loads it.
// Many IPTV providers switch off the playlist download (get.php) and block browsers, but keep the app API
// (player_api.php) that IPTV apps use. Browsers can't call it (no CORS headers), so this forwards those calls only.
// Only the channel list passes through here: the video itself goes straight from the provider to the player app.
// It stores nothing and logs nothing.
import type { Config } from "@netlify/functions"

const ORIGINS = new Set(["https://jfortress0.github.io"])
// Player names providers commonly accept, most widely accepted first (same list as the TV app, data/Live.kt).
const USER_AGENTS = ["IPTVSmartersPro", "okhttp/4.12.0", "VLC/3.0.20 LibVLC/3.0.20"]

function cors(origin: string | null): Record<string, string> {
  if (!origin || !ORIGINS.has(origin)) return {}
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Expose-Headers": "X-Jarvis-UA",
    "Access-Control-Max-Age": "86400",
    Vary: "Origin",
  }
}

export default async (req: Request) => {
  const h = cors(req.headers.get("origin"))
  if (!h["Access-Control-Allow-Origin"]) return new Response("Forbidden", { status: 403 })
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: h })
  if (req.method !== "GET") return new Response("Method not allowed", { status: 405, headers: h })

  let target: URL
  try {
    target = new URL(new URL(req.url).searchParams.get("u") || "")
  } catch {
    return new Response("Bad link", { status: 400, headers: h })
  }
  // Only an Xtream Codes app API call: http(s), path ending in player_api.php, with a username.
  if (!/^https?:$/.test(target.protocol) || !/\/player_api\.php$/.test(target.pathname) || !target.searchParams.get("username")) {
    return new Response("Not allowed", { status: 404, headers: h })
  }
  const host = target.hostname
  if (host === "localhost" || /^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|0\.)/.test(host) || host.endsWith(".internal")) {
    return new Response("Not allowed", { status: 404, headers: h })
  }

  let lastStatus = 502
  for (const ua of USER_AGENTS) {
    let r: Response
    try {
      r = await fetch(target.toString(), { headers: { "User-Agent": ua, Accept: "*/*" }, redirect: "follow", signal: AbortSignal.timeout(50_000) })
    } catch {
      lastStatus = 502
      continue
    }
    if (!r.ok) { lastStatus = r.status; continue }
    return new Response(r.body, {
      status: 200,
      headers: { ...h, "Content-Type": r.headers.get("content-type") || "application/json", "Cache-Control": "no-store", "X-Jarvis-UA": ua },
    })
  }
  return new Response(JSON.stringify({ error: "provider_refused", status: lastStatus }), { status: 502, headers: { ...h, "Content-Type": "application/json" } })
}

export const config: Config = { path: "/iptv" }
