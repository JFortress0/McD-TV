# Jarvis: handoff notes

Read this first when you pick the project up in a new session. It records the current state, how the pieces fit and what comes next. `CONFIG-MAP.md` lists every setting: where it is entered, stored and synced.

Last updated: 2026-10-18.

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
- **Ask Jarvis:** needs a Jarvis key on the Control page. The key is stored on the TV and shared only with linked TVs (HouseSync, encrypted). The UI never names the model provider.
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

### ui/ and player/

- `HomeScreen.kt`, `BrowseScreens.kt` (Jarvis search, Library, Services, Noise, Genres), `CatalogScreens.kt` (Browse menu and grids), `DetailScreen.kt`, `SourcesScreen.kt`, `PersonScreen.kt`.
- Live TV: `LiveScreens.kt` (channel list and Live Games), `GamesScreen.kt`, `LivePlayer.kt` (channel up / down), `MultiviewScreen.kt` (one ExoPlayer per tile, released on exit).
- **Live TV rail (October 20, 2026):** Multiview, then one "Live Games" entry, then CHANNELS (Search, Favorites, My Guide, Recent, sections, All). The leagues are chips inside Live Games. A fresh open puts focus on the first league chip (NFL Games in season); DOWN reaches the first live game. Before, Favorites took the first focus whenever it had channels, and the rail scrolled the leagues above it out of view. Each profile can hide leagues (pref `live_leagues_hidden@pN`, default WNBA, MLS and Premier League hidden); hidden leagues cost no ESPN requests. Entertainment is grouped by network (`LiveOrganizer.networkKey`, `feedScore`, `groupNetworks`, `personalize`): one row per network with its best feed, RIGHT opens the other feeds under it, networks holding a favorite or recent channel first (that feed leads), then US networks A to Z, with letter chips. Favorite teams per profile (pref `live_fav_teams@pN`, `FavoriteTeam` and `MyTeams` in `Games.kt`; Dad defaults to Eagles, Phillies, Flyers, 76ers): their games sit first in their league under YOUR TEAMS with a gold "★ MY TEAM", and a "★ My Teams" chip left of the leagues collects them across leagues. Settings > Addons and Live TV > My teams removes (OK) and adds teams from ESPN's team list (`Games.teams`, one request per league per app run). QA: `.github/qa/make_m3u.py` and `live_favs.py`.
- `AskJarvisScreen.kt` is the stand-alone Ask Jarvis page (QA screen `ask`). The search box uses the same `Jarvis.ask`.
- `ProfilesScreen.kt`, `SettingsScreen.kt` (also Phone & Computer Setup and Real-Debrid sign-in), `RdCloudScreen.kt`
- **Settings (October 19, 2026):** two panes. The rail on the left lists App updates, This TV, Profiles, Playback, Addons and Live TV, Jarvis Wall, Network, About and help; the right side shows the focused category. `SettingsScreen.kt` draws it, `SettingsKit.kt` holds the rows, the spacing and type scales and the dialogs, `SettingsModel.kt` holds the pure logic (focus moves, update text) with tests in `app/src/test/java/com/mcd/tv/ui/`. Rules: the remote moves through rows only. A text value is an Edit row; OK opens a dialog with a plain EditText and the keyboard; Done saves; one BACK closes both (`onKeyPreIme`). Choices open a list dialog. Destructive rows are red, sit at the end of their category and ask first. A fresh open lands on App updates (RIGHT, OK checks); a return from a screen Settings opened restores the category and row. QA deep link: `--es screen settings:<id>` (`updates`, `tv`, `profiles`, `playback`, `sources`, `wall`, `network`, `about`). The Settings ring in the top bar shows an amber dot when an update is ready.
- `data/RecentErrors.kt`: the last 10 problems shown on this TV (playback, sources, updates), for Settings > About and help. Pref `recent_errors`; `update_check` keeps the last check. Both this TV only., `WebScreen.kt` (browser with a D-pad pointer; route `Screen.Sports` in `SportsScreens.kt`).
- Look: `Theme.kt`, `Hud.kt`, `Common.kt`, `Components.kt`.
- `player/PlayerScreen.kt`: Media3 ExoPlayer. Saves progress every 15 s and on exit, pauses when the app leaves the screen, retries network errors, jumps back to the live edge. `TvPlayerView.kt` handles LEFT / RIGHT seek and BACK. `ui/UpNext.kt` shows the 10 s Up Next card between episodes.

### Relay protocol

- The TV makes a random link id and a 256-bit key and shows them as a QR code. The key sits after `#` in the link, so browsers never send it anywhere.
- Messages go through ntfy.sh. The page posts to `mcdtv-<id>-in`; the TV answers on `mcdtv-<id>-out`.
- Each message is `"v1:" + base64url(iv(12) || AES-256-GCM(deflate-raw(JSON)))`. The relay only sees random bytes.
- Commands include `hello`, `set` (settings), `house_join`, `house_leave`, `magnet`, `web_init`, `rd_resolve`, `progress`, `ask`, `open`, `play` and `watchlist`.
- Browsers can't call the Real-Debrid API: it sends no CORS headers. The web app's Play here uses one of two routes. (1) The device signed in to Real-Debrid itself (Settings > Real-Debrid on this device). Its calls go through `rd-proxy/`, a Netlify function at `jarvis-rd.netlify.app` that adds CORS headers and stores nothing. (2) Otherwise it sends `rd_resolve` to the TV, the TV makes the RD calls and sends back playable links. The TV's RD token never leaves the TV.
- "New link" in Phone & Computer Setup makes a new id and key, which disconnects old devices.
- **Who each TV is (October 18, 2026):** every TV has a per-device name, pref `tv_label` (TV Settings > This TV, or the Control page > This TV; never a HouseSync shared key), and a platform from the real maker and model (`data/TvIdentity.kt`: a known-model table with real models only, else Amazon = "Fire TV", Google or Chromecast = "Google TV", else "Android TV"). `tv_link` on the house topic carries `label`, `platform`, `maker`, `model` and `version`; `state` and `web_init` carry `device_id`, `tv_label`, `platform`, `model`, `version` and `now_playing`. Phones keep every TV in `mcdtv_tvs` as `{id, key, name, house, dev, label, platform, model, version, seen}` (name = label, else platform). The web app's header pill names the linked TV and, with more than one TV known, opens a TV picker; Play on TV and Open on TV go to the linked TV's own topic and carry `to: <device id>`, which every other TV ignores. Nothing is ever broadcast.
- **Now playing:** `data/NowPlaying.kt` is set by `PlayerScreen`; HouseSync posts `{"type":"tv_status","from","name","label","platform","now":{media,id,name,season,episode,playing,position_ms,duration_ms,ts}|null,"ts"}` on the house topic on play, pause, stop or a new title only, at most once per 10 s (a change inside the gap goes out when the gap ends). No position-only updates: phones count forward from the last event while it says playing. `state` and `web_init` also carry `now_playing`. The web app reads the house topic once (cached 60 s, newest per device id) when Settings or the TV picker opens. No polling loop.
- **Jarvis Wall from the phone:** relay commands `wall_start`, `wall_stop` (optional `mode`) and `wall_mode`. Start pushes `Screen.Wall`, Stop pops it, and the mode (pref `wall_mode`, per TV: "" = photos and calendar, "photos" = photos only) applies live through `Wall.mode`. WallScreen had no wallpaper-only mode before; the photos-only branch is a 5-line addition. How often the month calendar shows is pref `wall_cycle` per TV, "every/stays" minutes (default "30/1": the last minute of every half hour; "0/0" = never), pure logic in `data/WallCycle.kt`. It is set on the Control page (Show the full calendar, The calendar stays for), in TV Settings, through `set` (`wall_cycle`) and through `wall_start` / `wall_mode` (`cycle`). Before Oct 19 the month showed one minute in every ten. The Control page has Start, Stop and the mode for the linked TV, and Start and Stop for every other TV in its list (sent to that TV's topic with its key and `to`).

### HouseSync

- Every TV has a house id and key. TVs in the same house share keys, addons, playlist, Real-Debrid and preferences. Profile data (history, My List, favorites) syncs separately through `data/sync/ProfileSync.kt` on the same topic; see `docs/ROADMAP-sync.md`.
- Each TV listens on `mcdtv-<house id>-house`. Changes are sent encrypted (same format as the relay), in parts under ntfy's 4 KB limit, stamped with the change time. A TV takes a copy only when it is newer.
- "Use its settings here" on the Control page joins another TV's house. "Copy once" copies everything except Real-Debrid and does not link the TVs.

### Jarvis Wall (`wall/`, `data/Wall.kt`, `ui/WallScreen.kt`)

- Photo frame plus merged family calendar for a wall TV (a Fire Stick in the Hisense Roku TV's HDMI port). QA screen `wall`.
- `wall/` is a separate Netlify project, `jarvis-wall.netlify.app`, base directory `wall`. `/api/feed` merges the iCal links in the env var `WALL_CALENDARS` (they never leave Netlify) and adds Open-Meteo weather. `/api/photos` keeps photos in Netlify Blobs; the phone page at `/` uploads them. See `wall/README.md`.
- Keys: `WALL_KEY` (read only, goes on the Control page) and `UPLOAD_CODE` (photo page). Prefs `wall_url` and `wall_key` sync between linked TVs. `wall_start` (open straight into the Wall) and `wall_cache` (last feed) are per TV.
- Screen: photo on the left 62%, clock, weather, agenda and a "Due soon" box (calendars of kind `school`) on the right. Minute 9 of every 10 shows the month grid. OK switches views (holds 10 minutes), LEFT / RIGHT change photo. Screen kept on; dims 11 PM to 6 AM.
- No java.time: days are yyyymmdd Ints (`Wall.Day`), because minSdk is 24.
- Baby countdown: a light blue card under the weather. The date comes from "Baby due date" on the Control page (pref `wall_due`, shared with linked TVs), else an all-day calendar event called "Due date" or with "baby" and "due" in its title. The label and headline use "Baby's first name" from the Control page (pref `wall_baby`), never stored in the repo. Logic in `data/BabyCountdown.kt`. It hides 3 weeks after the date.

### Web pages (`docs/`, GitHub Pages)

- **Web Settings (October 19, 2026):** an overview list with a status line per category (Your TVs, Profile, Real-Debrid, Playback, Addons, Keys, House and devices, Help); each opens `#/settings/<id>`. Wide screens keep the list on the left. Saved keys and tokens show as status (Connected, Set, last worked); a box to paste a new one opens only after Replace or Add. Destructive actions sit in a red group and ask first. No storage key changed.

- `docs/index.html`: the Control page (QR link target).
- `docs/app/`: the Jarvis web app (PWA with a service worker).
- `docs/install.html`: install guide. `docs/get.html`: redirect to the newest APK.

## Build, CI and QA

- Push to `main`, or "Run workflow" in the Actions tab. `build.yml` runs unit tests, builds a signed release APK and publishes a Release (about 2 minutes). Version is `0.2.<run number>`; the version code is the run number.
- `qa.yml` runs the APK in an Android TV emulator (`.github/qa/run-qa.sh`): screens open and show the right text, setup page, relay end to end, a sample M3U, remote navigation, crash and freeze checks. Results are in the run summary and the `qa-results` artifact.
- Pushes that only touch `docs/`, Markdown, `rd-proxy/`, `tools/webtest/` or `.claude/` skip the APK build and emulator QA. Changes to `docs/` run Web QA instead (`.github/workflows/web-qa.yml`). A newer push cancels a run in progress.
- Cloud sessions cannot reach Google Maven, so they cannot build. CI is the build. Before pushing, syntax-check Kotlin with `tree-sitter-kotlin` (pip) and web scripts with `node --check`.
- Web app tests: `python3 tools/webtest/srv.py &` then `node tools/webtest/qa.mjs`.

## House rules

1. J adds addons, playlists and websites himself on the Control page. Do not hardcode third-party addons, piracy sites, IPTV providers or torrent indexers, and do not configure them for J. The app stays source-agnostic, like Stremio, Kodi and TiviMate.
2. User-facing text: plain short sentences, no em dashes. Do not name the model provider in the app UI (the provider console link next to the Jarvis key field is the one exception).
3. Android regexes are ICU: escape `]` and `}` inside `Regex(...)`.

## Open items

1. **Netlify budget:** relay deploys cost 15 credits each (300 a month on the free plan). See CLAUDE.md. The old account server (`server/`) was removed in October 2026; HouseSync, ProfileSync and the house store replace it.
2. **Next features:** see BACKLOG.md (ranked upgrades from the October 2026 review, by area).
3. **Housekeeping:** update GitHub Actions versions (Node 20 deprecation warnings).
