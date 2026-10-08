// McD TV account server.
// One small file, no dependencies: needs only Node.js 20 or newer.
// Stores accounts and each account's McD TV data (lists, history, settings) in data/db.json.
//
// Run:   node server.js
// Env:   PORT (default 8787)
//        INVITE_CODE  if set, people need this code to create an account
//        DATA_DIR     where db.json lives (default ./data)
"use strict";

const http = require("node:http");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const PORT = Number(process.env.PORT || 8787);
const INVITE = (process.env.INVITE_CODE || "").trim();
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, "data");
const DB_FILE = path.join(DATA_DIR, "db.json");
const MAX_BODY = 2 * 1024 * 1024; // 2 MB per sync is plenty

// ---------------- storage ----------------
fs.mkdirSync(DATA_DIR, { recursive: true });
let db = { users: {}, sessions: {} };
if (fs.existsSync(DB_FILE)) db = JSON.parse(fs.readFileSync(DB_FILE, "utf8"));

let saveTimer = null;
function save() {
  // Batch writes, then write to a temp file and rename so a crash never corrupts the database.
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const tmp = DB_FILE + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify(db));
    fs.renameSync(tmp, DB_FILE);
  }, 200);
}

// ---------------- auth helpers ----------------
const sha256 = (s) => crypto.createHash("sha256").update(s).digest("hex");

function hashPassword(pw, salt = crypto.randomBytes(16).toString("hex")) {
  const hash = crypto.scryptSync(pw, salt, 64).toString("hex");
  return { salt, hash };
}

function checkPassword(pw, user) {
  const { hash } = hashPassword(pw, user.salt);
  return crypto.timingSafeEqual(Buffer.from(hash, "hex"), Buffer.from(user.hash, "hex"));
}

function newSession(username) {
  const token = crypto.randomBytes(32).toString("hex");
  db.sessions[sha256(token)] = { username, created: Date.now() };
  save();
  return token;
}

function userFor(req) {
  const m = /^Bearer (\w+)$/.exec(req.headers.authorization || "");
  if (!m) return null;
  const s = db.sessions[sha256(m[1])];
  return s ? db.users[s.username] || null : null;
}

// Pending TV sign-ins (in memory; codes last 10 minutes).
const pairs = new Map();

// Slow down password guessing: max 10 failed logins per IP per 15 minutes.
const failures = new Map();
function tooManyFailures(ip) {
  const f = failures.get(ip);
  if (!f) return false;
  if (Date.now() - f.since > 15 * 60 * 1000) { failures.delete(ip); return false; }
  return f.count >= 10;
}
function noteFailure(ip) {
  const f = failures.get(ip) || { count: 0, since: Date.now() };
  f.count += 1;
  failures.set(ip, f);
}

// ---------------- http helpers ----------------
function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) });
  res.end(body);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error("too large")); req.destroy(); return; }
      chunks.push(c);
    });
    req.on("end", () => {
      try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {}); }
      catch { reject(new Error("bad json")); }
    });
    req.on("error", reject);
  });
}

const validName = (u) => typeof u === "string" && /^[a-zA-Z0-9_.-]{3,32}$/.test(u);
const validPw = (p) => typeof p === "string" && p.length >= 6 && p.length <= 200;

// ---------------- routes ----------------
async function route(req, res) {
  const ip = req.headers["cf-connecting-ip"] || req.socket.remoteAddress || "?";
  const url = new URL(req.url, "http://x");
  const p = url.pathname;

  if (req.method === "GET" && (p === "/" || p === "/admin")) {
    const html = fs.readFileSync(path.join(__dirname, "admin.html"));
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Content-Length": html.length, "Cache-Control": "no-store" });
    return res.end(html);
  }

  if (req.method === "GET" && p === "/health") {
    return send(res, 200, { ok: true, app: "McD TV server", users: Object.keys(db.users).length, inviteRequired: !!INVITE });
  }

  if (req.method === "POST" && p === "/api/register") {
    const b = await readJson(req);
    const username = String(b.username || "").trim().toLowerCase();
    if (INVITE && String(b.invite || "").trim() !== INVITE) return send(res, 403, { error: "Wrong invite code" });
    if (!validName(username)) return send(res, 400, { error: "Username: 3-32 letters, numbers, . _ -" });
    if (!validPw(b.password)) return send(res, 400, { error: "Password: at least 6 characters" });
    if (db.users[username]) return send(res, 409, { error: "That username is taken" });
    const { salt, hash } = hashPassword(b.password);
    db.users[username] = { username, salt, hash, created: Date.now(), data: null, dataUpdatedAt: 0 };
    save();
    return send(res, 200, { token: newSession(username), username });
  }

  if (req.method === "POST" && p === "/api/login") {
    if (tooManyFailures(ip)) return send(res, 429, { error: "Too many attempts. Try again in 15 minutes." });
    const b = await readJson(req);
    const user = db.users[String(b.username || "").trim().toLowerCase()];
    if (!user || !validPw(b.password) || !checkPassword(b.password, user)) {
      noteFailure(ip);
      return send(res, 401, { error: "Wrong username or password" });
    }
    return send(res, 200, { token: newSession(user.username), username: user.username });
  }

  // ---- TV pairing: the TV shows a code, you approve it on the web page while signed in. ----
  if (req.method === "POST" && p === "/api/pair/start") {
    const code = String(crypto.randomInt(100000, 1000000));
    const id = crypto.randomBytes(16).toString("hex");
    pairs.set(id, { code, created: Date.now(), token: null, username: null });
    return send(res, 200, { id, code, expiresIn: 600 });
  }
  if (req.method === "GET" && p === "/api/pair/poll") {
    const pr = pairs.get(url.searchParams.get("id") || "");
    if (!pr || Date.now() - pr.created > 600_000) return send(res, 404, { error: "Code expired" });
    if (!pr.token) return send(res, 202, { waiting: true });
    pairs.delete(url.searchParams.get("id"));
    return send(res, 200, { token: pr.token, username: pr.username });
  }

  // Everything below needs a signed-in user.
  const user = userFor(req);
  if (!user) return send(res, 401, { error: "Sign in first" });

  if (req.method === "POST" && p === "/api/pair/approve") {
    const b = await readJson(req);
    const code = String(b.code || "").replace(/\D/g, "");
    for (const pr of pairs.values()) {
      if (pr.code === code && !pr.token && Date.now() - pr.created < 600_000) {
        pr.token = newSession(user.username);
        pr.username = user.username;
        return send(res, 200, { ok: true });
      }
    }
    return send(res, 404, { error: "No TV is showing that code. Check the code on the TV." });
  }

  if (req.method === "POST" && p === "/api/logout") {
    const m = /^Bearer (\w+)$/.exec(req.headers.authorization || "");
    if (m) delete db.sessions[sha256(m[1])];
    save();
    return send(res, 200, { ok: true });
  }

  if (req.method === "GET" && p === "/api/me") {
    return send(res, 200, { username: user.username, created: user.created, dataUpdatedAt: user.dataUpdatedAt });
  }

  // The TV app keeps its lists and settings in one JSON blob. Newest write wins.
  if (req.method === "GET" && p === "/api/sync") {
    return send(res, 200, { data: user.data, updatedAt: user.dataUpdatedAt });
  }
  if (req.method === "PUT" && p === "/api/sync") {
    const b = await readJson(req);
    if (typeof b.data !== "object" || b.data === null) return send(res, 400, { error: "Missing data" });
    const at = Number(b.updatedAt) || Date.now();
    if (at < user.dataUpdatedAt) return send(res, 409, { error: "Server copy is newer", updatedAt: user.dataUpdatedAt });
    user.data = b.data;
    user.dataUpdatedAt = at;
    save();
    return send(res, 200, { ok: true, updatedAt: at });
  }

  // Checks an addon link for the web page (browsers can't fetch most addons directly).
  if (req.method === "POST" && p === "/api/addon/check") {
    const b = await readJson(req);
    let url = String(b.url || "").trim();
    if (url.startsWith("stremio://")) url = "https://" + url.slice("stremio://".length);
    if (!/^https?:\/\//.test(url)) url = "https://" + url;
    if (!url.endsWith("manifest.json")) url = url.replace(/\/+$/, "") + "/manifest.json";
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(15000) });
      if (!r.ok) return send(res, 400, { error: `Addon answered ${r.status}` });
      const m = await r.json();
      const resources = (m.resources || []).map((x) => (typeof x === "string" ? x : x.name));
      return send(res, 200, { url, name: m.name || "Addon", description: m.description || "", streams: resources.includes("stream") });
    } catch (e) {
      return send(res, 400, { error: "Could not load that addon: " + e.message });
    }
  }

  if (req.method === "POST" && p === "/api/password") {
    const b = await readJson(req);
    if (!checkPassword(String(b.old || ""), user)) return send(res, 401, { error: "Current password is wrong" });
    if (!validPw(b.new)) return send(res, 400, { error: "Password: at least 6 characters" });
    Object.assign(user, hashPassword(b.new));
    save();
    return send(res, 200, { ok: true });
  }

  return send(res, 404, { error: "Not found" });
}

const server = http.createServer((req, res) => {
  route(req, res).catch((e) => send(res, 400, { error: e.message || "Bad request" }));
});

if (require.main === module) {
  server.listen(PORT, () => console.log(`McD TV server on port ${PORT}${INVITE ? " (invite code required)" : ""}`));
}
module.exports = { server };
