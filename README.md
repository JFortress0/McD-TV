# Jarvis

A personal streaming app for Fire TV, Google TV and Android TV, with an original holographic heads-up display look.

Jarvis is a player and an interface. It ships with no content sources. You add every source (stream links, Real-Debrid, playlists, addons) inside the app.

**Install on a TV:** in the Downloader app, type `jfortress0.github.io/McD-TV/get`. Step-by-step guide for Fire TV, Google TV, Android TV and Android phones: [jfortress0.github.io/McD-TV/install.html](https://jfortress0.github.io/McD-TV/install.html).

**Building it yourself? Follow [SETUP.md](SETUP.md).** It takes you from this folder to the app on your TV.

## What it does (v0.2)

Jarvis copies the HuberTV layout and runs it natively on the TV. It talks straight to TMDB, your Real-Debrid account and the addons you install. It needs no server.

- **Intro:** short animated open. Any button skips it.
- **Look:** an original sci-fi heads-up display. Near-black glass panels, cyan light, chamfered cards with corner brackets on focus, a faint grid behind every page, arc gauges for scores and a live clock in the top bar.
- **Home:** rotating hero banner, Continue Watching, Watchlist, Favorites, Trending, Popular, Now Playing, Top Rated, Family Movie Night, Browse by Year.
- **Title page:** backdrop, poster, cast, similar titles, seasons and episodes. Buttons: Play, Choose Source, Favorite, Watchlist, Mark as Watched, Background Noise, Not for me.
- **Source picker:** every stream your addons return, with quality, size and a CACHED badge. Play picks the best one by itself.
- **Real-Debrid:** sign in with a code at real-debrid.com/device. You type nothing on the TV.
- **My List:** Watchlist, Favorites, Background Noise shows, Watch History.
- **Profiles:** "Who's watching?" after the intro: Dad, Mom and Kids (rename in Settings). Each has its own history, Continue Watching, My List, favorites and Live TV favorites; settings are shared. Kids gets family and animation rows on Home.
- **Live TV:** your M3U playlist by category, plus live channel and event lists from your addons. Now / next from the playlist's program guide (XMLTV), favorites (Menu button), recent channels, last channel, channel search. In the player, UP / DOWN change the channel.
- **Services:** browse the Netflix, Prime, Disney+, Hulu, Apple TV+, Peacock, Max, Paramount+, Crunchyroll and Starz catalogs. Title pages show which service carries a title and open that service's own app.
- **Background Noise:** pick shows once. Random episodes keep playing.
- **Genres:** movies and shows by genre, sorted by Popular or Top Rated.
- **Title origin filter:** show all countries, US-made only, or hide Asian-made titles (Settings or the web page).
- **Phone setup:** paste keys and links from your phone over home Wi-Fi.
- **Accounts (optional):** sign in to your own Jarvis server and your lists, history, addons and Real-Debrid link follow you to any TV. See `server/README.md`.
- **Websites:** a built-in browser with a remote-controlled pointer, for sites you add in Phone setup (Home > More > Websites).
- **Settings:** US only, Slow connection (smaller files first), intro on or off.

Jarvis ships with no content sources. You add the TMDB key, addons, Real-Debrid account and playlist yourself.

### Remote controls in the player

- OK: show the control bar.
- LEFT / RIGHT (control bar hidden): jump back or ahead 10 seconds.
- CC button: subtitles on or off. Gear button: audio track and speed.
- BACK: hide the control bar, then press BACK again to leave the player.
- Live TV only: UP / DOWN (control bar hidden) change the channel. Menu adds or removes a favorite.

## Project map

```
Jarvis/
├── .github/workflows/build.yml   Cloud build. Makes the APK on every push.
├── app/
│   ├── build.gradle.kts          App settings: name, version, libraries
│   └── src/main/
│       ├── AndroidManifest.xml   Tells Fire TV this is a TV app
│       ├── java/com/mcd/tv/
│       │   ├── MainActivity.kt   Starts the app and moves between screens
│       │   ├── data/             TMDB, Real-Debrid, addons, library, live TV, scores, phone setup
│       │   ├── ui/               Intro, Home, title page, sources, sports, settings, cards
│       │   └── player/           Video player and remote-key handling
│       └── res/
│           ├── raw/mcd_intro.ogg Intro music and voiceover
│           ├── drawable-xhdpi/   TV launcher banner
│           └── mipmap-*/         App icon
├── server/                       Optional account server (runs on a spare Mac)
├── gradle/libs.versions.toml     Library versions, all in one place
├── keystore/                     Your private signing key. Never uploaded.
├── README.md                     This file
└── SETUP.md                      Step-by-step install checklist
```

## How the cloud build works

1. You push a change to the `main` branch with GitHub Desktop.
2. GitHub Actions starts `build.yml` on a GitHub computer.
3. The build signs the APK with your key (the `KEYSTORE_BASE64` secret).
4. GitHub publishes a Release with two copies of the APK:
   - `McD-TV-v0.2.N.apk` keeps a record of each version.
   - `Jarvis.apk` always holds the newest build.
5. On the TV, the Downloader app uses this link. It never changes:
   `https://github.com/JFortress0/McD-TV/releases/latest/download/Jarvis.apk`

Each build gets a higher version number, so the TV installs it as an update. Your settings stay.

## Where to find the APK on GitHub

- **Newest APK:** open your repository page. Click **Releases** on the right side. Click `Jarvis.apk` under the top release.
- **One build's APK:** click the **Actions** tab. Click a run. Scroll to **Artifacts**.
- **Roll back:** in **Releases**, download an older `McD-TV-v0.2.N.apk` and install it.

## Roadmap

| Phase | Adds |
|---|---|
| 1 | Intro, home, settings, player |
| 2 | HuberTV-style browsing, sources, Real-Debrid, library, sports, live TV (this version) |
| 3 | Live TV now / next guide, favorites and channel surfing (done). Next: full guide grid, Xtream login, network files (SMB / WebDAV) |
| 4 | Plain-English search, Trakt sync, profiles with PIN, speed tuning |

## Tech stack

Kotlin, Jetpack Compose for TV, Media3 / ExoPlayer, Gradle (Kotlin DSL, version catalog), GitHub Actions. Minimum Android 7.0 (API 24).

Fonts: Orbitron (headings, wordmark, numbers) and Rajdhani (body text and buttons), bundled in `app/src/main/res/font` under the SIL Open Font License. See `FONT-LICENSE-HUD.txt`. The web pages load the same two fonts from Google Fonts.
