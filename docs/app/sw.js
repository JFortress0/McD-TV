// Jarvis web app service worker: caches the app shell only, so the app opens fast and installs.
// index.html is network-first (updates show up right away). Opening the app waits at most 3 seconds for the
// network, then uses the cached copy (the page checks for a newer version itself and offers it).
// API, relay, image and stream requests are never handled or cached here: they go straight to the network.
//
// The page registers "sw.js?v=<BUILD>", so every new version is a new worker with its own cache
// ("jarvis-shell-<BUILD>"). Old shell caches are deleted when the new worker takes over.
"use strict";
const VERSION = new URL(self.location.href).searchParams.get("v") || "0";
const PREFIX = "jarvis-shell-";
const CACHE = PREFIX + VERSION.replace(/[^A-Za-z0-9._-]/g, "").slice(0, 40);
const SHELL = ["./", "./index.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png", "./apple-touch-icon.png"];
const SHELL_TIMEOUT = 3000;
const scopeUrl = new URL(self.registration.scope);

self.addEventListener("install", (e) => {
  // cache: "reload" skips the browser's HTTP cache, so the new cache holds the new files.
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: "reload" })))).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys()
    .then((ks) => Promise.all(ks.filter((k) => k !== CACHE && k.startsWith(PREFIX)).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

/** Which shell file a request is for ("" when it is not one: then the network handles it as usual). */
function shellPath(req) {
  if (req.method !== "GET") return "";
  const u = new URL(req.url);
  if (u.origin !== scopeUrl.origin || !u.pathname.startsWith(scopeUrl.pathname)) return "";
  const rest = u.pathname.slice(scopeUrl.pathname.length);
  if (rest === "" || rest === "index.html") return "./index.html";
  const p = "./" + rest;
  return SHELL.includes(p) ? p : "";
}

const cachedShell = () => caches.open(CACHE).then((c) => c.match("./index.html").then((r) => r || c.match("./")))
  .then((r) => r || caches.match("./index.html"));

/** index.html: network first. Opening the app (a navigation) falls back to the cache after 3 s or on failure. */
function shellResponse(e) {
  const req = e.request;
  const net = fetch(req, { cache: "no-store" }).then((r) => {
    if (r.ok && r.type === "basic") { const copy = r.clone(); caches.open(CACHE).then((c) => c.put("./index.html", copy)).catch(() => {}); }
    return r;
  });
  e.waitUntil(net.then(() => {}, () => {})); // a slow answer still refreshes the cache after the timeout
  // The page's own update check (fetch of index.html?u=...) must see the network, not the cache.
  if (req.mode !== "navigate") return net.catch(() => cachedShell().then((r) => r || Response.error()));
  return new Promise((resolve) => {
    let done = false;
    const finish = (r) => { if (!done && r) { done = true; resolve(r); } };
    const timer = setTimeout(() => { cachedShell().then(finish, () => {}); }, SHELL_TIMEOUT);
    net.then((r) => { clearTimeout(timer); finish(r); },
      () => { clearTimeout(timer); cachedShell().then((r) => finish(r || Response.error()), () => finish(Response.error())); });
  });
}

self.addEventListener("fetch", (e) => {
  const p = shellPath(e.request);
  if (!p) return;
  if (p === "./index.html") { e.respondWith(shellResponse(e)); return; }
  // Icons and manifest: cache first.
  e.respondWith(caches.match(p).then((r) => r || fetch(e.request)));
});
