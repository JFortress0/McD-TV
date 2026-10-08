# McD TV: handoff notes

Read this first when picking the project up in a new session (any model). It records the current state, how the pieces fit, and what comes next.

Last updated: 2026-10-08.

## Current state

- **Installed and working on J's Fire Stick:** v0.2.5. J approved the UI overhaul.
- **TMDB:** the key is built into each APK from the GitHub secret `TMDB_API_KEY`.
- **Real-Debrid:** connected on the TV with the device code.
- **GitHub:** repo `github.com/JFortress0/McD-TV` (public). Each push to `main` builds a signed APK and publishes a Release.
- **Install / update on the TV:** Downloader app, address `https://github.com/JFortress0/McD-TV/releases/latest/download/McD-TV.apk`.
- **Local copy:** `~/Desktop/McD TV` on J's MacBook Air is a git repo. J pushes with GitHub Desktop.
- **Secrets:** the signing key is in `keystore/` (git-ignored) and in the GitHub secret `KEYSTORE_BASE64`. Never commit `keystore/`.

## How the app works

| Piece | File(s) | Notes |
|---|---|---|
| Navigation | `MainActivity.kt` | Back stack of `Screen` objects. Account sync runs every 2 minutes. The setup web page starts in `onStart` and stops in `onStop`. |
| Theme | `ui/Theme.kt`, `ui/Common.kt`, `ui/Components.kt` | Graphite background, red glow pill buttons, cyan focus, Exo 2 headings (`res/font`). The intro keeps its own italic style (`introStyle`). |
| Catalog | `data/Tmdb.kt` | Every list passes through `parseList`, which applies the origin filter (All / US only / Hide Asian; default Hide Asian). Top Rated = US titles with high vote counts. Adult titles are always off. |
| Sources | `data/Addons.kt` | Stremio addon protocol client. The user adds addon URLs; none ship with the app. Sorted cached first, then quality (or smaller files in Slow mode). |
| Real-Debrid | `data/RealDebrid.kt` | Device-code sign-in (open-source client id). Hash to link: addMagnet, select file, unrestrict. `RdCloud` lists and plays the user's RD library and adds magnets. |
| Player | `player/` | Media3 ExoPlayer. Remote: LEFT/RIGHT seek 10 s, BACK hides the controls. Saves progress every 15 s. Next episode plays automatically. |
| Library | `data/Library.kt` | Favorites, Watchlist, history / Continue Watching, Background Noise shows, hidden titles. Stored in SharedPreferences as JSON. |
| Live / Sports | `data/Live.kt`, `ui/SportsScreens.kt` | M3U playlists (user supplied), ESPN public scoreboard, matching playlist channels per game. |
| Websites | `ui/WebScreen.kt` | Built-in browser with a D-pad pointer. Blocks pop-up windows and cross-site redirects without a click. Sites are user-added (Sports > Websites). |
| Setup page | `data/PhoneSetup.kt` | Web page served by the TV on port 8642 while the app is open. Sets TMDB key, addons, M3U, websites, stream link, magnet, server URL. |
| Accounts | `data/Account.kt`, `ui/AccountScreen.kt`, `server/` | Optional Node server (no dependencies) with accounts, sync and the web Control page (`server/admin.html`). Sign in on the TV with a code. Not deployed yet. |

## Open items for the next session

1. **The Mac's Chrome can't open the TV setup page** (`http://192.168.1.79:8642`); J's phone can.
   - Check from the Mac's Terminal with `curl -m 5 http://192.168.1.79:8642`.
   - Look for a VPN, iCloud Private Relay, a firewall or Chrome's Local Network Access setting.
   - If the Mac can't be fixed, deploy the account server and use its Control page.
2. **J will enter his sources himself** on the setup page from his phone: addon URLs and the sports websites he named. Do not hardcode third-party addons, piracy sites or torrent indexers into the repo or the app. The app stays source-agnostic, like Stremio, Kodi and TiviMate.
3. **Account server:** run it on the spare M1 Mac (`server/setup-mac.sh`), then set up Tailscale Funnel for access away from home. Only needed for multiple users or managing sources away from home.
4. **Features learned from HuberTV's TV app:**
   - FFmpeg audio decoders for DTS / TrueHD: Media3 decoder extension, e.g. the nextlib `media3ext` artifact. Check its license and which Media3 version it matches.
   - Profile picker.
   - In-app update check against GitHub Releases.
5. **Housekeeping:** update GitHub Actions to newer versions (`setup-java@v5`; Node 20 deprecation warnings).

## What HuberTV does (for reference)

- Next.js web app plus a native Android TV app (Kotlin, Compose, Hilt, Retrofit, Media3 + FFmpeg extension). The TV app is a thin client of their server.
- **Server endpoints:**
  - Catalog: `api/content/*`
  - Sources: `api/debrid/sources`, which uses a single addon, Torrentio, configured with the owner's Real-Debrid key
  - Playback: `api/debrid/resolve`
  - TV sign-in: `api/auth/pair`
  - Profiles: `api/family`
  - Updates: `api/app/version`
- All HuberTV users share the owner's Real-Debrid account. McD TV gives each user their own Real-Debrid link instead, because Real-Debrid flags accounts that stream from several homes at once.

## Build and release

- Push to `main`, or click "Run workflow" in the Actions tab. The build takes about 2 minutes.
- Versions are `0.2.<run number>`; the version code is the run number, so each build updates over the last.
- Cloud sessions can't reach Google Maven or Gradle, so local builds there fail. CI is the build. `tree-sitter-kotlin` (pip) is a quick syntax check before pushing.
- Server tests: `cd server && node test.js`.
