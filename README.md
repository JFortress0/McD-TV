# McD TV

A personal streaming app for Fire TV, Google TV and Android TV.
Pronounced "Mick-Dee Tee Vee."

McD TV is a player and an interface. It ships with no content sources. You add every source (stream links, Real-Debrid, playlists, addons) inside the app.

**First time? Follow [SETUP.md](SETUP.md).** It takes you from this folder to the app on your TV.

## What it does (v0.2)

McD TV copies the HuberTV layout and runs it natively on the TV. It talks straight to TMDB, your Real-Debrid account and the addons you install. It needs no server.

- **Intro:** broadcast-style open with the "This is McD TV" voiceover. Any button skips it.
- **Home:** rotating hero banner, Continue Watching, Watchlist, Favorites, Trending, Popular, Now Playing, Top Rated, Family Movie Night, Browse by Year.
- **Title page:** backdrop, poster, cast, similar titles, seasons and episodes. Buttons: Play, Choose Source, Favorite, Watchlist, Mark as Watched, Mindless TV, Not for me.
- **Source picker:** every stream your addons return, with quality, size and a CACHED badge. Play picks the best one by itself.
- **Real-Debrid:** sign in with a code at real-debrid.com/device. You type nothing on the TV.
- **My List:** Watchlist, Favorites, Mindless TV shows, Watch History.
- **Sports:** live scores and today's games for NFL, college football, NBA, MLB, NHL, college hoops and the Premier League. Select a game to see matching channels from your own playlist.
- **Live TV:** your M3U playlist, grouped by category.
- **Services:** browse the Netflix, Prime, Disney+, Hulu, Apple TV+, Peacock, Max, Paramount+, Crunchyroll and Starz catalogs.
- **Mindless TV:** pick shows once. Random episodes keep playing.
- **Calendar:** upcoming episodes for shows on your Watchlist.
- **Phone setup:** paste keys and links from your phone over home Wi-Fi.
- **Settings:** US only, Slow connection (smaller files first), intro on or off.

McD TV ships with no content sources. You add the TMDB key, addons, Real-Debrid account and playlist yourself.

### Remote controls in the player

- OK: show the control bar.
- LEFT / RIGHT (control bar hidden): jump back or ahead 10 seconds.
- CC button: subtitles on or off. Gear button: audio track and speed.
- BACK: hide the control bar, then press BACK again to leave the player.

## Project map

```
McD TV/
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
   - `McD-TV.apk` always holds the newest build.
5. On the TV, the Downloader app uses this link. It never changes:
   `https://github.com/JFortress0/McD-TV/releases/latest/download/McD-TV.apk`

Each build gets a higher version number, so the TV installs it as an update. Your settings stay.

## Where to find the APK on GitHub

- **Newest APK:** open your repository page. Click **Releases** on the right side. Click `McD-TV.apk` under the top release.
- **One build's APK:** click the **Actions** tab. Click a run. Scroll to **Artifacts**.
- **Roll back:** in **Releases**, download an older `McD-TV-v0.2.N.apk` and install it.

## Roadmap

| Phase | Adds |
|---|---|
| 1 | Intro, home, settings, player |
| 2 | HuberTV-style browsing, sources, Real-Debrid, library, sports, live TV (this version) |
| 3 | Live TV program guide (XMLTV), Xtream login, network files (SMB / WebDAV) |
| 4 | Goose-style plain-English search, Trakt sync, profiles with PIN, speed tuning |

## Tech stack

Kotlin, Jetpack Compose for TV, Media3 / ExoPlayer, Gradle (Kotlin DSL, version catalog), GitHub Actions. Minimum Android 7.0 (API 24).
