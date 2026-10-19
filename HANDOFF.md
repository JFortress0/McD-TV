# Jarvis: handoff notes

Read this first when you pick the project up in a new session. It records the current state, how the pieces fit and what comes next.

Last updated: 2026-10-19.

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
| `SpotifyCore.kt`, `Spotify.kt`, `SpotifyVault.kt` | Spotify phase 1: links and URIs, sign-in, Web API, playback through the Spotify app on the TV, token vault. See "Spotify" below. |
| `PhoneSetup.kt` | Home Wi-Fi fallback page on port 8642. Runs only while Phone & Computer Setup is open, or in a QA launch. |

### ui/ and player/

- `HomeScreen.kt`, `BrowseScreens.kt` (Jarvis search, Library, Services, Noise, Genres), `CatalogScreens.kt` (Browse menu and grids), `DetailScreen.kt`, `SourcesScreen.kt`, `PersonScreen.kt`.
- Live TV: `LiveScreens.kt` (channel list and Games block), `GamesScreen.kt`, `LivePlayer.kt` (channel up / down), `MultiviewScreen.kt` (one ExoPlayer per tile, released on exit).
- `AskJarvisScreen.kt` is the stand-alone Ask Jarvis page (QA screen `ask`). The search box uses the same `Jarvis.ask`.
- `ProfilesScreen.kt`, `SettingsScreen.kt` (also Phone & Computer Setup and Real-Debrid sign-in), `RdCloudScreen.kt`, `WebScreen.kt` (browser with a D-pad pointer; route `Screen.Sports` in `SportsScreens.kt`).
- Look: `Theme.kt`, `Hud.kt`, `Common.kt`, `Components.kt`.
- `player/PlayerScreen.kt`: Media3 ExoPlayer. Saves progress every 15 s and on exit, pauses when the app leaves the screen, retries network errors, jumps back to the live edge. `TvPlayerView.kt` handles LEFT / RIGHT seek and BACK. `ui/UpNext.kt` shows the 10 s Up Next card between episodes.

### Relay protocol

- The TV makes a random link id and a 256-bit key and shows them as a QR code. The key sits after `#` in the link, so browsers never send it anywhere.
- Messages go through ntfy.sh. The page posts to `mcdtv-<id>-in`; the TV answers on `mcdtv-<id>-out`.
- Each message is `"v1:" + base64url(iv(12) || AES-256-GCM(deflate-raw(JSON)))`. The relay only sees random bytes.
- Commands include `hello`, `set` (settings), `house_join`, `house_leave`, `magnet`, `web_init`, `rd_resolve`, `progress`, `ask`, `open`, `play`, `watchlist`, `wall_start`, `wall_stop`, `wall_mode`, `spotify_play`, `spotify_control` and `spotify_list`.
- Browsers can't call the Real-Debrid API: it sends no CORS headers. The web app's Play here uses one of two routes. (1) The device signed in to Real-Debrid itself (Settings > Real-Debrid on this device). Its calls go through `rd-proxy/`, a Netlify function at `jarvis-rd.netlify.app` that adds CORS headers and stores nothing. (2) Otherwise it sends `rd_resolve` to the TV, the TV makes the RD calls and sends back playable links. The TV's RD token never leaves the TV.
- "New link" in Phone & Computer Setup makes a new id and key, which disconnects old devices.
- **Who each TV is (October 18, 2026):** every TV has a per-device name, pref `tv_label` (TV Settings > This TV, or the Control page > This TV; never a HouseSync shared key), and a platform from the real maker and model (`data/TvIdentity.kt`: a known-model table with real models only, else Amazon = "Fire TV", Google or Chromecast = "Google TV", else "Android TV"). `tv_link` on the house topic carries `label`, `platform`, `maker`, `model` and `version`; `state` and `web_init` carry `device_id`, `tv_label`, `platform`, `model`, `version` and `now_playing`. Phones keep every TV in `mcdtv_tvs` as `{id, key, name, house, dev, label, platform, model, version, seen}` (name = label, else platform). The web app's header pill names the linked TV and, with more than one TV known, opens a TV picker; Play on TV and Open on TV go to the linked TV's own topic and carry `to: <device id>`, which every other TV ignores. Nothing is ever broadcast.
- **Now playing:** `data/NowPlaying.kt` is set by `PlayerScreen`; HouseSync posts `{"type":"tv_status","from","name","label","platform","now":{media,id,name,season,episode,playing,position_ms,duration_ms,ts}|null,"ts"}` on the house topic on play, pause, stop or a new title only, at most once per 10 s (a change inside the gap goes out when the gap ends). No position-only updates: phones count forward from the last event while it says playing. `state` and `web_init` also carry `now_playing`. The web app reads the house topic once (cached 60 s, newest per device id) when Settings or the TV picker opens. No polling loop.
- **Jarvis Wall from the phone:** relay commands `wall_start`, `wall_stop` (optional `mode`) and `wall_mode`. Start pushes `Screen.Wall`, Stop pops it, and the mode (pref `wall_mode`, per TV: "" = photos and calendar, "photos" = photos only) applies live through `Wall.mode`. WallScreen had no wallpaper-only mode before; the photos-only branch is a 5-line addition. The Control page has Start, Stop and the mode for the linked TV, and Start and Stop for every other TV in its list (sent to that TV's topic with its key and `to`).

- **Spotify (October 19, 2026):** relay commands `spotify_play {uri}`, `spotify_control {action: pause | resume | next | previous}` and `spotify_list {offset}`. Each one must name its TV with `to` (the web app also sends the same id as `tv`). The TV refuses a Spotify command with no target and ignores one for another TV. `spotify_list` answers with `{type: "spotify_list", playlists: [[name, uri, songs], ...], more, next_offset, music, note}`, cut to fit one relay message. `tv_status`, `state` and `web_init` carry `music: {source: "spotify", track, artist, playing, ts} | null`. `state` also carries `spotify_client_id` and `spotify_connected`.

### Spotify (phase 1, branch `feature/spotify-in-jarvis`)

Goal: J picks a Spotify playlist in Jarvis and it plays while Jarvis stays on screen. Spotify closed its mobile streaming SDKs in 2022, so Jarvis never streams audio. It tells the official Spotify app on the same TV what to play.

- **Sign-in:** Spotify's Android Authorization Library (`com.spotify.android:auth:5.0.0`). Jarvis asks for a token. The library turns that into Authorization Code with PKCE: it makes the verifier, swaps the code and returns an access token and a refresh token. There is no client secret. Redirect URI: `jarvis-tv://spotify-callback` (`SpotifyConfig.REDIRECT_URI`, the manifest filter and a unit test must match).
- **Scopes:** playlist-read-private, playlist-read-collaborative, user-library-read, user-read-playback-state, user-modify-playback-state, app-remote-control.
- **Client ID:** configuration, not a secret, never committed. The Control page field (pref `spotify_client_id`, this TV only) wins. A local build can bake one in with `spotifyClientId=...` in `local.properties` or `~/.gradle/gradle.properties`, or the env var `SPOTIFY_CLIENT_ID`.
- **Tokens:** only in `SpotifyVault` on the TV that signed in. AES-256-GCM with a key in the Android Keystore, in its own file `spotify_vault.xml`, which the backup rules (`res/xml`) leave out. Never in Prefs, HouseSync, the Control link, the relay, logs or git. `SpotifyTokens.toString()` hides them, and `SpotifyRedact` hides tokens, codes and Bearer values in every message Jarvis shows or sends. Release builds drop `Log.d` and `Log.v`, because the sign-in library logs the one-time code at debug level.
- **Playback, two routes:** (1) App Remote (`app/libs/spotify-app-remote-release-0.8.0.aar`, Apache 2.0) when the phone build of Spotify (`com.spotify.music`) is on the TV. (2) Spotify Connect through the Web API player calls when only the Spotify TV app (`com.spotify.tv.android`) is there. App Remote binds only to the phone app (its manifest lists `com.spotify.music` only), so a Fire TV with the normal Spotify TV app uses Connect. Jarvis finds this TV's Connect device by the name J picked, else the only device of type TV, else a name that contains this TV's name or model. With more than one candidate, the screen asks J to pick one.
- **Now playing:** App Remote sends live events. Connect has none, so the Spotify screen reads `/me/player` every 15 s while it is open, and once 1.5 s after each command. Music on another device (J's phone) does not count as this TV's music.
- **Missing app or not signed in:** one plain message and one button (Get Spotify, Open Spotify or Connect Spotify). Jarvis never opens another app unless J presses that button.
- **Profiles:** the Spotify tile (Home > More, Browse > More, web app Browse) shows on the Dad profile (p1) only. Other profiles are unchanged. A `spotify_play` from the phone plays on the TV whatever profile is open.
- **Screens:** TV `ui/SpotifyScreen.kt` (QA screen `spotify`), web app `#/music`, Control page section "Spotify".
- **CI on pull requests:** Build APK, QA and Web QA now run on pull requests too. Only `main` publishes a Release, so a pull request never updates the TVs. The pull request's APK is in the run's artifacts.

#### Setup J must do once (real devices)

1. Go to developer.spotify.com/dashboard and sign in with the Spotify account that will play the music. Spotify Premium is needed to choose what plays.
2. Press **Create app**. Any name works (for example "Jarvis"). Under **Redirect URIs**, add exactly `jarvis-tv://spotify-callback`. Under **Which API/SDKs are you planning to use**, tick **Web API** and **Android**.
3. For Android, Spotify asks for a package name and a SHA1 fingerprint. Package name: `com.mcd.tv`. Fingerprint: on the MacBook, in the `McD TV` folder, run `keytool -list -v -keystore keystore/mcdtv.jks -alias mcdtv` (the password is in `app/build.gradle.kts`) and copy the `SHA1:` line. Save the app.
4. In the app's **User Management**, add the email of every Spotify account that will sign in (a new app is in Development mode).
5. Copy the **Client ID** from the app's settings page. Do not copy the Client secret; Jarvis does not use it.
6. On the gym TV: install **Spotify** from the TV's app store, open it, sign in and play any song once. Then press Home. This makes the TV show up as a Spotify Connect device.
7. Check that the gym TV has a web browser (Fire TV: Silk Browser or Firefox from the Appstore). The Spotify sign-in page opens in it. A TV with the phone build of Spotify signs in through Spotify instead.
8. On the phone or computer, open the gym TV's Control page, paste the Client ID in **Spotify**, and press **Save to TV**.
9. On the gym TV, open Jarvis on the Dad profile. Go to Home > More > **Spotify** and press **Connect Spotify**. Sign in, press **Agree**, and the browser returns to Jarvis.
10. Pick a playlist. If Jarvis asks which device is the gym TV, pick it once; Jarvis remembers it.

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
