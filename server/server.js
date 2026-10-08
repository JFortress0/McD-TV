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

  if (req.method === "GET" && (p === "/" || p === "/health")) {
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

  // Everything below needs a signed-in user.
  const user = userFor(req);
  if (!user) return send(res, 401, { error: "Sign in first" });

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
