// Jarvis web app service worker: caches the app shell only, so the app opens fast and installs.
// index.html is network-first (updates show up right away). API, relay, image and stream requests
// are never handled or cached here: they go straight to the network.
"use strict";
const CACHE = "jarvis-shell-v1";
const SHELL = ["./", "./index.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png", "./apple-touch-icon.png"];
const scopeUrl = new URL(self.registration.scope);

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
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

self.addEventListener("fetch", (e) => {
  const p = shellPath(e.request);
  if (!p) return;
  if (p === "./index.html") {
    // Network first, cached copy when offline.
    e.respondWith(
      fetch(e.request, { cache: "no-store" })
        .then((r) => { if (r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put("./index.html", copy)); } return r; })
        .catch(() => caches.match("./index.html").then((r) => r || caches.match("./")))
    );
    return;
  }
  // Icons and manifest: cache first.
  e.respondWith(caches.match(p).then((r) => r || fetch(e.request)));
});
