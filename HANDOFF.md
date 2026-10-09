# Jarvis: handoff notes

Read this first when you pick the project up in a new session. It records the current state, how the pieces fit and what comes next.

Last updated: 2026-10-08.

## Names that must not change

The app was called "McD TV" and is now "Jarvis". Only the visible name changed. These identifiers stay as they are, because installed TVs and saved links depend on them:

- Package and application id `com.mcd.tv`, theme `Theme.McdTV`, Kotlin names such as `McdColors` and `McdTheme`.
- SharedPreferences file `mcdtv_settings` and every preference key.
- ntfy topics `mcdtv-<id>-in`, `mcdtv-<id>-out` and `mcdtv-<house id>-house`.
- Browser storage keys `mcdtv_*` on the Control page and web app.
- Repo `github.com/JFortress0/McD-TV`, Pages site `jfortress0.github.io/McD-TV`, release files `McD-TV.apk` and `McD-TV-v0.2.N.apk` (old install links point at them).

## Current state

- **Install / update:** Downloader app, address `jfortress0.github.io/McD-TV/get`. It redirects to `releases/latest/download/Jarvis.apk`. Guide for every device: `docs/install.html`.
- **TMDB:** built into each APK from the GitHub secret `TMDB_API_KEY`. A key saved on the Control page wins.
- **MDBList (IMDb and Rotten Tomatoes scores):** secret `MDBLIST_API_KEY` or the Control page.
- **Ask Jarvis:** needs a Jarvis key on the Control page. The key is stored on the TV and shared only with linked TVs (HouseSync, encrypted). It never goes to the account server. The UI never names the model provider.
- **Local copy:** `~/Desktop/McD TV` on J's MacBook Air is a git repo. J pushes with GitHub Desktop.
- **Secrets:** the signing key is in `keystore/` (git-ignored) and in the GitHub secret `KEYSTORE_BASE64`. Never commit `keystore/`.

## Architecture

The app is one Activity with Compose for TV screens. Code lives in three packages under `app/src/main/java/com/mcd/tv/`.

### Navigation (`MainActivity.kt`)

A back stack of `Screen` objects. Each entry keeps its own saved state, so Back restores scroll position, search text and filters. The QA run opens screens directly with `--es screen <name>` (for example `settings`, `live`, `games`, `ask`, `detail:movie:603`).

### data/

| File | What it does |
|---|---|
| `Prefs.kt` | All settings in SharedPreferences. Per-profile keys end in `@p1`..`@p3`. |
| `Tmdb.kt` | Catalog. Every list passes the origin filter. Adult titles are always off. |
| `Addons.kt`, `StreamInfo.kt` | Stremio addon client. Streams are parsed, ranked (cached first, then quality) and de-duplicated. |
| `RealDebrid.kt` | Device-code sign-in, magnet to link, RD Cloud listing. Retries once on a 401 after a token refresh. |
| `Library.kt` | Favorites, Watchlist, history and Continue Watching, Background Noise, hidden titles. JSON in Prefs. |
| `Live.kt`, `LiveCatalog.kt`, `Epg.kt` | M3U or Xtream Codes playlist (movies and series skipped), channel clean-up and sections, XMLTV guide. Cached 6 hours. |
| `Games.kt` | ESPN schedules and matching of games to playlist channels. |
| `Jarvis.kt` | Ask Jarvis: model call, JSON parsing, TMDB lookup of each guess. |
| `Relay.kt` | Encrypted link between the TV and the Control page / web app. |
| `HouseSync.kt` | Shared settings between linked TVs. |
| `PhoneSetup.kt` | Home Wi-Fi fallback page on port 8642. Runs only while Phone & Computer Setup is open, or in a QA launch. |
| `Account.kt` | Optional account server client. See "Open items". |

### ui/ and player/

- `HomeScreen.kt`, `BrowseScreens.kt` (Jarvis search, Library, Services, Noise, Genres), `CatalogScreens.kt` (Browse menu and grids), `DetailScreen.kt`, `SourcesScreen.kt`, `PersonScreen.kt`.
- Live TV: `LiveScreens.kt` (channel list and Games block), `GamesScreen.kt`, `LivePlayer.kt` (channel up / down), `MultiviewScreen.kt` (one ExoPlayer per tile, released on exit).
- `AskJarvisScreen.kt` is the stand-alone Ask Jarvis page (QA screen `ask`). The search box uses the same `Jarvis.ask`.
- `ProfilesScreen.kt`, `SettingsScreen.kt` (also Phone & Computer Setup and Real-Debrid sign-in), `AccountScreen.kt`, `RdCloudScreen.kt`, `WebScreen.kt` (browser with a D-pad pointer; route `Screen.Sports` in `SportsScreens.kt`).
- Look: `Theme.kt`, `Hud.kt`, `Common.kt`, `Components.kt`.
- `player/PlayerScreen.kt`: Media3 ExoPlayer. Saves progress every 15 s and on exit, pauses when the app leaves the screen, retries network errors, jumps back to the live edge. `TvPlayerView.kt` handles LEFT / RIGHT seek and BACK. `ui/UpNext.kt` shows the 10 s Up Next card between episodes.

### Relay protocol

- The TV makes a random link id and a 256-bit key and shows them as a QR code. The key sits after `#` in the link, so browsers never send it anywhere.
- Messages go through ntfy.sh. The page posts to `mcdtv-<id>-in`; the TV answers on `mcdtv-<id>-out`.
- Each message is `"v1:" + base64url(iv(12) || AES-256-GCM(deflate-raw(JSON)))`. The relay only sees random bytes.
- Commands include `hello`, `set` (settings), `house_join`, `house_leave`, `magnet`, `web_init`, `rd_token`, `progress`, `ask`, `open`, `play` and `watchlist`.
- "New link" in Phone & Computer Setup makes a new id and key, which disconnects old devices.

### HouseSync

- Every TV has a house id and key. TVs in the same house share keys, addons, playlist, Real-Debrid and preferences. Profile data (history, My List, favorites) syncs separately through `data/sync/ProfileSync.kt` on the same topic; see `docs/ROADMAP-sync.md`.
- Each TV listens on `mcdtv-<house id>-house`. Changes are sent encrypted (same format as the relay), in parts under ntfy's 4 KB limit, stamped with the change time. A TV takes a copy only when it is newer.
- "Use its settings here" on the Control page joins another TV's house. "Copy once" copies everything except Real-Debrid and does not link the TVs.

### Web pages (`docs/`, GitHub Pages)

- `docs/index.html`: the Control page (QR link target).
- `docs/app/`: the Jarvis web app (PWA with a service worker).
- `docs/install.html`: install guide. `docs/get.html`: redirect to the newest APK.

## Build, CI and QA

- Push to `main`, or "Run workflow" in the Actions tab. `build.yml` runs unit tests, builds a signed release APK and publishes a Release (about 2 minutes). Version is `0.2.<run number>`; the version code is the run number.
- `qa.yml` runs the APK in an Android TV emulator (`.github/qa/run-qa.sh`): screens open and show the right text, setup page, relay end to end, a sample M3U, remote navigation, crash and freeze checks. Results are in the run summary and the `qa-results` artifact.
- Pushes that only touch `docs/`, Markdown or `server/` skip build and QA. A newer push cancels a run in progress.
- Cloud sessions cannot reach Google Maven, so they cannot build. CI is the build. Before pushing, syntax-check Kotlin with `tree-sitter-kotlin` (pip) and web scripts with `node --check`.
- Server tests: `cd server && node test.js`.

## House rules

1. J adds addons, playlists and websites himself on the Control page. Do not hardcode third-party addons, piracy sites, IPTV providers or torrent indexers, and do not configure them for J. The app stays source-agnostic, like Stremio, Kodi and TiviMate.
2. User-facing text: plain short sentences, no em dashes. Do not name the model provider in the app UI (the provider console link next to the Jarvis key field is the one exception).
3. Android regexes are ICU: escape `]` and `}` inside `Regex(...)`.

## Open items

1. **Account server:** `server/` works and has tests, but nothing in the app or the Control page sets the server address (`server_url`), so the Account page cannot be used. Either add a field for it or remove the feature. HouseSync covers most of what it was for.
2. **Next features:** full EPG grid, mini-guide overlay in the live player, profile PINs, Trakt sync, subtitle styling, FFmpeg audio (DTS / TrueHD), in-app update check, Android TV Watch Next row.
3. **Housekeeping:** update GitHub Actions versions (Node 20 deprecation warnings).
