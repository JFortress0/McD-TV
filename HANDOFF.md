# McD TV: handoff notes

Read this first when picking the project up in a new session (any model). It records the current state, how the pieces fit, and what comes next.

Last updated: 2026-10-08.

## Current state

- **Installed on J's Fire TV:** a v0.2.x build. Each new Release replaces it when J reinstalls from Downloader.
- **TMDB:** the key is built into each APK from the GitHub secret `TMDB_API_KEY`.
- **Real-Debrid:** connected on the TV with the device code.
- **GitHub:** repo `github.com/JFortress0/McD-TV` (public). Each push to `main` builds a signed APK and publishes a Release.
- **Install / update on the TV:** Downloader app, address `jfortress0.github.io/McD-TV/get` (redirects to `https://github.com/JFortress0/McD-TV/releases/latest/download/McD-TV.apk`). Guide for every device: `docs/install.html`.
- **Phone and computer setup:** the TV shows a QR code (Settings > Phone & Computer Setup). It opens the Control page (`docs/index.html`, GitHub Pages), which talks to the TV through an encrypted ntfy.sh relay (`data/Relay.kt`). This works on any network. The home-Wi-Fi page on port 8642 is a fallback and runs only while that screen is open.
- **QA:** `.github/workflows/qa.yml` runs the app in an Android TV emulator on every code push (`.github/qa/run-qa.sh`): screens, setup page, relay end to end, a sample M3U playlist, remote navigation, crash and freeze checks. Docs-only pushes skip build and QA.
- **Local copy:** `~/Desktop/McD TV` on J's MacBook Air is a git repo. J pushes with GitHub Desktop.
- **Secrets:** the signing key is in `keystore/` (git-ignored) and in the GitHub secret `KEYSTORE_BASE64`. Never commit `keystore/`.

## How the app works

| Piece | File(s) | Notes |
|---|---|---|
| Navigation | `MainActivity.kt` | Back stack of `Screen` objects. Each entry keeps its own saved state (`SaveableStateHolder`), so Back restores scroll, search and filters. Account sync runs every 2 minutes. |
| Theme | `ui/Theme.kt`, `ui/Common.kt`, `ui/Components.kt` | Graphite background, red glow pill buttons, cyan focus, Exo 2 headings (`res/font`). The intro keeps its own italic style (`introStyle`). |
| Catalog | `data/Tmdb.kt` | Every list passes through `parseList`, which applies the origin filter (All / US only / Hide Asian; default Hide Asian). Top Rated = US titles with high vote counts. Adult titles are always off. |
| Sources | `data/Addons.kt` | Stremio addon protocol client. The user adds addon URLs; none ship with the app. Sorted cached first, then quality (or smaller files in Slow mode). |
| Real-Debrid | `data/RealDebrid.kt` | Device-code sign-in (open-source client id). Hash to link: reuse an existing torrent or addMagnet, wait for the file list, pick the file (addon fileIdx, then SxxEyy name, then largest), unrestrict. Torrents added for a play that fails are deleted. Token refresh retries once on 401. `RdCloud` lists and plays the user's RD library. |
| Player | `player/`, `ui/UpNext.kt` | Media3 ExoPlayer. Remote: LEFT/RIGHT seek 10 s, BACK hides the controls, MENU sets a sleep timer. Saves progress; pauses when the app leaves the screen. Live streams recover from falling behind and retry network errors. Episodes end with a 10 s Up Next card. |
| Library | `data/Library.kt` | Favorites, Watchlist, history / Continue Watching, Background Noise shows, hidden titles. Stored in SharedPreferences as JSON. |
| Live TV | `data/Live.kt`, `data/Epg.kt`, `ui/LiveScreens.kt`, `ui/LivePlayer.kt` | M3U playlist (user supplied; movie/series entries skipped) plus addon live catalogs. Now/next from the playlist's XMLTV guide, favorites (MENU), recents, last channel, search. Live player: UP/DOWN change channel. |
| Sports | `ui/SportsScreens.kt` | ESPN public scoreboard, matching playlist channels per game, user-added websites. |
| Websites | `ui/WebScreen.kt` | Built-in browser with a D-pad pointer. Blocks pop-up windows and cross-site redirects without a click. Sites are user-added (Sports > Websites). |
| Setup page | `data/PhoneSetup.kt` | Home-Wi-Fi fallback page on port 8642, only while Phone & Computer Setup is open (or a QA launch). Shows addon hosts, never full addon URLs. `/qa-relay` answers only in QA mode. |
| Accounts | `data/Account.kt`, `ui/AccountScreen.kt`, `server/` | Optional Node server (no dependencies) with accounts, sync and the web Control page (`server/admin.html`). Sign in on the TV with a code. Not deployed yet. |

## Open items for the next session

1. **J's sources:** J adds addons, playlists and websites himself on the Control page. Do not hardcode third-party addons, piracy sites, IPTV providers or torrent indexers into the repo or the app, and do not configure them for J. The app stays source-agnostic, like Stremio, Kodi and TiviMate.
2. **MDBList key** (IMDb and Rotten Tomatoes scores): J creates it at mdblist.com and pastes it on the Control page or into the GitHub secret `MDBLIST_API_KEY`.
3. **Next features** (see the morning report of 2026-10-08): full EPG grid, mini-guide overlay in the live player, profiles with PIN, Trakt sync, subtitle styling, FFmpeg audio (DTS / TrueHD), in-app update check, Android TV Watch Next row.
4. **Account server** (optional): run it on the spare M1 Mac (`server/setup-mac.sh`), then Tailscale Funnel.
5. **Housekeeping:** update GitHub Actions versions (Node 20 deprecation warnings).

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

- Push to `main`, or click "Run workflow" in the Actions tab. The build takes about 2 minutes; QA about 10.
- A newer push cancels a build or QA run still in progress.
- When nobody can push from the Mac, changes can go up through the GitHub website (Add file > Upload files, one folder per commit). After that, run `git pull` in the Mac copy so GitHub Desktop stays in step.
- Versions are `0.2.<run number>`; the version code is the run number, so each build updates over the last.
- Cloud sessions can't reach Google Maven or Gradle, so local builds there fail. CI is the build. `tree-sitter-kotlin` (pip) is a quick syntax check before pushing.
- Server tests: `cd server && node test.js`.
