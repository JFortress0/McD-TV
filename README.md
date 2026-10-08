# McD TV

A personal streaming app for Fire TV, Google TV and Android TV.
Pronounced "Mick-Dee Tee Vee."

McD TV is a player and an interface. It ships with no content sources. You add every source (stream links, Real-Debrid, playlists, addons) inside the app.

**First time? Follow [SETUP.md](SETUP.md).** It takes you from this folder to the app on your TV.

## Phase 1 (this version)

- Broadcast-style intro with the "This is McD TV" voiceover. Press any button to skip. Turn it off in Settings.
- Home screen with rows of cards. Use the remote's arrow keys and OK button.
- Settings screen. Paste any stream link there. It appears on Home as "My Stream."
- Video player (Media3 / ExoPlayer). It plays HLS, DASH, MP4 and MKV.
- Remote controls in the player:
  - OK: show the control bar.
  - LEFT / RIGHT (control bar hidden): jump back or ahead 10 seconds.
  - CC button: turn subtitles on or off.
  - Gear button: choose the audio track and playback speed.
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
│       │   ├── data/             Saved settings and the test streams
│       │   ├── ui/               Intro, Home, Settings, colors, cards
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
   - `McD-TV-v0.1.N.apk` keeps a record of each version.
   - `McD-TV.apk` always holds the newest build.
5. On the TV, the Downloader app uses this link. It never changes:
   `https://github.com/YOUR-USERNAME/McD-TV/releases/latest/download/McD-TV.apk`

Each build gets a higher version number, so the TV installs it as an update. Your settings stay.

## Where to find the APK on GitHub

- **Newest APK:** open your repository page. Click **Releases** on the right side. Click `McD-TV.apk` under the top release.
- **One build's APK:** click the **Actions** tab. Click a run. Scroll to **Artifacts**.
- **Roll back:** in **Releases**, download an older `McD-TV-v0.1.N.apk` and install it.

## Roadmap

| Phase | Adds |
|---|---|
| 1 | Intro, home, settings, player (this version) |
| 2 | Network files (SMB / WebDAV), TMDB posters, Continue Watching |
| 3 | Real-Debrid |
| 4 | Live TV: M3U, Xtream login, program guide |
| 5 | Stremio addon client, My List |
| 6 | Trakt sync, profiles with PIN, search, speed tuning |

## Tech stack

Kotlin, Jetpack Compose for TV, Media3 / ExoPlayer, Gradle (Kotlin DSL, version catalog), GitHub Actions. Minimum Android 7.0 (API 24).
