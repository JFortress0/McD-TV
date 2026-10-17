# Backlog

Ranked upgrades from the October 2026 review by the four focus agents (.claude/agents). Effort: S small, M medium, L large. "Relay" marks items that need a Netlify deploy (15 credits each; batch them, see CLAUDE.md). Everything else is free to ship.

## Done October 16

- Real-Debrid name filter: RD refuses names with WEB-DL, WEB.x264, WEB.H264, HDTV.x264 or HDTV.XviD (exact case, rule of October 14, 2026) with error 35. Those sources sort last and show "May be blocked" (`RdFilter`, TV and web). A source RD refused is skipped for 6 hours. Play best source tries 8 and does not stop early: RD refuses more names than the published tags. The rule changes often: update `RdFilter.TAGS` and `RD_TAGS` together.

## Done in the October 12 overnight run

- Live TV: Huntsville call signs go to Local even with "News" in the name (TV and web); a failed guide fetch retries after 90 s (the TV kept an empty guide for 6 hours); the web guide cache survives app launches (15 minutes, per playlist, no login stored); My Guide and For You use the event and game fallbacks; "Tap to play" in the web Live TV player.
- Web app: updates wait during playback, source lookups, sign-in and the hand-off question ("Update ready. Tap to update."); service worker cache tied to BUILD with a 3 s shell timeout; Settings > Recent errors; watchlist edits queue while the TV is off; the dead Websites route is gone; shared b64u and sha helpers.
- TV app: "Still watching?" after 3 automatic episodes; the Home hero holds still while focused; LEFT/RIGHT presses add up into one seek; HouseSync compares settings when they change (60 s fallback, 10 s after a failure) instead of every 4 s; the screen may sleep after 10 minutes paused; the update screen says leaving stops the download; one app-wide image loader (RGB565 JPEGs, bounded caches).
- Real-Debrid: plain messages for refusals; a refused token refresh asks to reconnect and no longer clears the sign-in on every linked TV.
- Web QA: TMDB and ntfy mocked as 200; checks for the update prompt, error log, watchlist queue, guide cache and Live TV tap to play.

## Done in the October 10 agent run (commits 269f649 to e1634b4)

- Live TV: West feeds keep their own guide (`gkey` / `LiveFill.guideKey`); event channels show their game as the current program; ESPN, FOX, CBS, SECN, ACCN and BTN rows with no guide show the live or next game from loaded schedules; Huntsville stations first in Local and Games. TV and web.
- Web app: a TV that is off fails in about 6 s (`tvAlive`); "Tap to play" when the iPhone blocks autoplay; play position saved on background (hidden, pagehide, freeze); Settings > Check network.
- TV app: R8 and resource shrinking on release; baseline profile with profileinstaller; "Try next source" when playback fails; Back returns to the row and tile on Home.
- Follow-ups: call-sign-only stations ("WHNT News 19") land in News, not Local (change `LO.section` and `sectionFor` together); give `LiveGuide.kt` and `LiveForYou` the `LiveFill.nowNext` fallbacks; add "Tap to play" to the web Live TV player; check Live TV load time on a Fire stick; mock TMDB and ntfy as 200 in Web QA so the "OK" path of Check network is tested.

## Done in the October 11 cleanup

- Removed the unused account server (`server/`) and the app's Account page and its 2-minute background loop.
- Web app: "TV online" now turns to "TV offline" when the TV stops answering; the live link to the TV reconnects after the app comes back from the background; Live TV makes 8 ESPN requests on open instead of 36; the search page no longer scrolls sideways on phones.
- TV app: "Join my other TVs" waits 10 s between checks instead of 3 s (it could trip ntfy.sh's rate limit for the whole house).
- Web QA in CI (`.github/workflows/web-qa.yml`, tests in `tools/webtest/`).
- CLAUDE.md with the Netlify budget rule, and four focus agents.

## Live TV (agent: live-tv)

1. Games matching parity with the TV: abbreviation pairs, one team plus "vs", guide titles near kickoff. M
2. TV guide: use the playlist's accepted user agent, keep a trimmed guide on disk for cold starts. M
3. "On now" strip on Favorites and Sports, plus a Comedy shortcut. S/M
4. Check My Guide and For You speed on a Fire stick now that they use the LiveFill fallbacks. S
- Relay (next batched deploy): block IPv6 private addresses and redirects to internal hosts in iptv.mts.

## Web and phone app (agent: web-app)

1. Apply a waiting update by itself when a busy state ends without a page change (closing the Live TV player). S
2. Recent errors: skip known harmless rejections (a TMDB 404 nobody waits for) so the list stays useful. S

## TV app (agent: tv-app)

1. Move watch history out of SharedPreferences into files (it is rewritten every 15 s on the main thread). M
2. Buffer settings for 1 GB sticks (Media3 LoadControl). S
3. Jarvis Wall photos now decode as RGB565 (app-wide image loader): if gradients band, add `allowRgb565(false)` to the Wall's image request. S
4. Start Jarvis by itself when the stick boots, for the Wall TV (may need an extra Fire OS permission). M
- Risks: every launch sends a house sync request that every other TV answers; "TV started" goes to ntfy on every launch.

## QA

- Web QA now runs on docs/ changes. Next: run the Kotlin unit tests as a fast job before the 40-minute emulator job; mock TMDB and ESPN in the emulator QA so a vendor outage can't fail it.
