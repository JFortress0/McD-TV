# Jarvis Real-Debrid relay (Netlify)

The web app (docs/app) can't call the Real-Debrid API from a browser: RD sends no CORS headers, so the browser blocks every reply. This Netlify function forwards the web app's requests to `api.real-debrid.com` and adds CORS headers for `https://jfortress0.github.io` only.

- Live at `https://jarvis-rd.netlify.app/rd/...` (Netlify project `jarvis-rd`, free tier).
- Only the RD calls the web app makes are allowed (see `ALLOWED` in `netlify/functions/rd.mts`).
- It stores and logs nothing. Each phone signs in to Real-Debrid once with a code (real-debrid.com/device) and keeps its own token in that browser.

Deploy: the `jarvis-rd` project is linked to this GitHub repo with base directory `rd-proxy`. A push that changes this folder redeploys it. By hand: `npx netlify-cli deploy --prod` from this folder.
