# Jarvis

A personal streaming app for Fire TV, Google TV and Android TV, with an original sci-fi heads-up display look.

Jarvis is a player and an interface. It ships with no content sources. You add every source (addons, Real-Debrid, an M3U playlist, websites) yourself on the Control page.

**Install on a TV:** in the Downloader app, type `jfortress0.github.io/McD-TV/get`. Step-by-step guide for Fire TV, Google TV, Android TV and Android phones: [jfortress0.github.io/McD-TV/install.html](https://jfortress0.github.io/McD-TV/install.html).

**Building it yourself?** Follow [SETUP.md](SETUP.md). For the code and how the pieces fit, read [HANDOFF.md](HANDOFF.md).

## Features

- **Intro:** a short HUD boot video. Any button skips it. Turn it off in Settings.
- **Profiles:** "Who's watching?" after the intro (Dad, Mom, Kids; rename in Settings). Each profile has its own history, Continue Watching, My List and Live TV favorites. The Kids profile gets family and animation rows on Home.
- **Home:** Continue Watching, a rotating hero, Live TV favorites, Suggested for You, Trending, Popular, Top Rated, New Releases, and a More row (Browse, Live TV, Games, Websites, Background Noise).
- **Jarvis (search):** one box for titles and plain-English questions. Type a name to search TMDB. Describe a title ("the movie where a guy relives the same day") and Ask Jarvis answers with its best guesses. Ask Jarvis needs a Jarvis key, added on the Control page.
- **Browse:** Trending, Top Rated, New Releases, By Year, By Language, Genres, Collections, streaming services (Netflix, Prime, Disney+ and more), Websites and Background Noise.
- **Title page:** backdrop, scores (TMDB, plus IMDb and Rotten Tomatoes with an MDBList key), trailer, cast, seasons and episodes, similar titles, the movie's collection. Play picks the best source by itself. Choose Source lists every stream.
- **Sources:** every stream your addons return, with quality, HDR, audio, size and an Instant badge for cached Real-Debrid files. CAM releases and very large files are hidden until you ask.
- **Real-Debrid:** sign in with a code at real-debrid.com/device. The RD Cloud page plays files already in your account.
- **Live TV:** your M3U playlist (or Xtream Codes link) by section, plus live catalogs from addons. Now and next from the playlist's XMLTV guide, favorites (Menu button), recents and search. In the player, UP and DOWN change the channel.
- **Games hub:** this week's NFL, college football, NBA, MLB, NHL, WNBA, MLS and Premier League games (ESPN schedule), each with the channels in your playlist that carry it.
- **Multiview:** watch 1, 2 or 4 playlist channels at once. OK picks which tile you hear.
- **Background Noise:** pick shows once. Random episodes keep playing.
- **Websites:** a built-in browser with a remote-controlled pointer, for sites you add on the Control page.
- **Shared settings across TVs:** on the Control page, "Use its settings here" links two TVs. Keys, addons, the playlist, Real-Debrid and preferences then stay the same on both. "Copy once" gives another TV a one-time copy without your Real-Debrid. Each profile's history, Continue Watching and My List also sync between linked TVs.
- **Control page:** scan the QR code in Settings > Phone & Computer Setup. The page talks to the TV through an encrypted relay, so it works from any network.
- **Web app:** [jfortress0.github.io/McD-TV/app/](https://jfortress0.github.io/McD-TV/app/) is Jarvis in a phone or computer browser. Browse, then Play on TV, or Play here on the phone. Sign in to Real-Debrid once on each phone to play with the TV off. It installs as an app (PWA).

## Remote controls in the player

- OK: show the control bar.
- LEFT / RIGHT (control bar hidden): jump back or ahead 10 seconds.
- CC button: subtitles. Gear button: audio track and speed.
- Menu: sleep timer (30, 60, 90 minutes or end of this video).
- BACK: hide the control bar, then BACK again to leave.
- Live TV: UP / DOWN change the channel. Menu adds or removes a favorite.

## Where settings live

- On the TV, in Android SharedPreferences (`mcdtv_settings`). Profile data uses keys ending in `@p1`, `@p2` or `@p3`.
- Keys and links are entered on the Control page and sent to the TV. Nothing is stored on a server.
- The TMDB key (and optionally an MDBList key) can be built into the APK from GitHub secrets.
- The Control page and web app keep their link to the TV in the browser's local storage.

## Project map

```
.github/workflows/   build.yml (APK + Release), qa.yml (emulator QA)
.github/qa/          run-qa.sh and the relay test used by QA
app/src/main/java/com/mcd/tv/
  MainActivity.kt    screens, back stack, QA launch hooks
  data/              TMDB, addons, Real-Debrid, library, live TV, guide, games, relay, house sync, prefs
  ui/                every screen and the HUD components
  player/            Media3 player and remote-key handling
app/src/main/res/    fonts, intro video, launcher icon and TV banner
docs/                GitHub Pages: Control page, web app, install guide, /get redirect
server/              optional account server (not in use)
```

## Build

Push to `main` and GitHub Actions builds a signed APK and publishes a Release. Each Release has `Jarvis.apk` (the install link points here), `McD-TV.apk` (older install links) and `McD-TV-v0.2.N.apk` (a copy per version). Each build has a higher version number, so the TV installs it as an update and keeps its settings.

## Tech stack

Kotlin, Jetpack Compose for TV, Media3 (ExoPlayer), Coil, Gradle (Kotlin DSL, version catalog), GitHub Actions. Minimum Android 7.0 (API 24).

Fonts: Orbitron (headings and numbers) and Rajdhani (body text), bundled under the SIL Open Font License. See `FONT-LICENSE-HUD.txt`.
