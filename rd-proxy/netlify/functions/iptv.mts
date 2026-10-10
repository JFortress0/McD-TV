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

  const wantWindow = new URL(req.url).searchParams.get("window") === "1"
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
    // "&window=1" on a guide request: send only what's on from now to six hours ahead. A provider's full
    // guide for one channel is about 150 KB; the web app needs a few shows of it.
    if (wantWindow && target.searchParams.get("action") === "get_simple_data_table") {
      try {
        const j = (await r.json()) as { epg_listings?: Array<Record<string, unknown>> }
        const now = Date.now() / 1000
        const list = (j.epg_listings || [])
          .filter((e) => Number(e.stop_timestamp) > now && Number(e.start_timestamp) < now + 6 * 3600)
          .map((e) => ({ title: e.title, start_timestamp: e.start_timestamp, stop_timestamp: e.stop_timestamp }))
        return new Response(JSON.stringify({ epg_listings: list }), {
          status: 200,
          headers: { ...h, "Content-Type": "application/json", "Cache-Control": "no-store", "X-Jarvis-UA": ua },
        })
      } catch {
        lastStatus = 502
        continue
      }
    }
    return new Response(r.body, {
      status: 200,
      headers: { ...h, "Content-Type": r.headers.get("content-type") || "application/json", "Cache-Control": "no-store", "X-Jarvis-UA": ua },
    })
  }
  return new Response(JSON.stringify({ error: "provider_refused", status: lastStatus }), { status: 502, headers: { ...h, "Content-Type": "application/json" } })
}

export const config: Config = { path: "/iptv" }
