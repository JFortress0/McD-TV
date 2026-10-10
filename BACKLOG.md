# Backlog

Ranked upgrades from the October 2026 review by the four focus agents (.claude/agents). Effort: S small, M medium, L large. "Relay" marks items that need a Netlify deploy (15 credits each; batch them, see CLAUDE.md). Everything else is free to ship.

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
2. Keep the web guide cache across app launches (15-minute TTL). S
3. TV guide: use the playlist's accepted user agent, keep a trimmed guide on disk for cold starts; give LiveGuide.kt and LiveForYou the LiveFill fallbacks. M
4. "On now" strip on Favorites and Sports, plus a Comedy shortcut. S/M
5. Call-sign-only stations ("WHNT News 19") go to Local, not News, on TV and web. S
- Bug: a failed guide fetch is cached as empty for 15 minutes; retry after 1 to 2 minutes instead. S
- Relay (next batched deploy): block IPv6 private addresses and redirects to internal hosts in iptv.mts.

## Web and phone app (agent: web-app)

1. Safe updates: don't reload for a new version during Live TV, a source lookup, sign-in or the hand-off dialog; show "tap to update". S
2. Watchlist edits survive the TV being off (queue and resend). M
3. Service worker: 3-second timeout on the shell fetch; tie the cache name to BUILD. S
4. A global unhandled-error log so silent failures are visible. S
5. "Tap to play" in the Live TV player too (same autoplay pattern as the old movie player). S
- Cleanup: `#/websites` is unreachable (remove or wire up); "Events & PPV" never occurs; duplicate helpers (b64u, sha, two visibilitychange listeners).

## TV app (agent: tv-app)

1. "Still watching?" after 3 automatic next episodes with no key press. S
2. Pause the Home hero carousel while it has focus. S
3. Smooth seeking: add up held LEFT/RIGHT presses and seek once. S
4. Memory: app-wide image loader with RGB565 and a bounded cache; buffer settings for 1 GB sticks. S/M
5. Move watch history out of SharedPreferences into files (it is rewritten every 15 s on the main thread). M
- Risks: HouseSync wakes every 4 s forever to compare settings; every launch sends a house sync request that every other TV answers; "TV started" goes to ntfy on every launch; keepScreenOn stays on while paused; leaving the update screen cancels the download silently.

## QA

- Web QA now runs on docs/ changes. Next: run the Kotlin unit tests as a fast job before the 40-minute emulator job; mock TMDB and ESPN in the emulator QA so a vendor outage can't fail it.
