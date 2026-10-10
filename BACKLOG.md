# Backlog

Ranked upgrades from the October 2026 review by the four focus agents (.claude/agents). Effort: S small, M medium, L large. "Relay" marks items that need a Netlify deploy (15 credits each; batch them, see CLAUDE.md). Everything else is free to ship.

## Done in the October 11 cleanup

- Removed the unused account server (`server/`) and the app's Account page and its 2-minute background loop.
- Web app: "TV online" now turns to "TV offline" when the TV stops answering; the live link to the TV reconnects after the app comes back from the background; Live TV makes 8 ESPN requests on open instead of 36; the search page no longer scrolls sideways on phones.
- TV app: "Join my other TVs" waits 10 s between checks instead of 3 s (it could trip ntfy.sh's rate limit for the whole house).
- Web QA in CI (`.github/workflows/web-qa.yml`, tests in `tools/webtest/`).
- CLAUDE.md with the Netlify budget rule, and four focus agents.

## Live TV (agent: live-tv)

1. Wider guide borrowing on the web (`gkey`): keep East/West apart (West feeds now borrow East times, 3 hours off), handle "ESPN 2"/"ESPN2", "&"/"and", strip "(backup)". S
2. Event channels show their own title: parse "NCAAF 05: Alabama vs Tennessee 3:30PM ET" into a NOW row. S
3. ESPN overlay: for ESPN, FOX, CBS, SECN, ACCN and BTN rows with no guide, show the live or next game from the schedule already loaded. M
4. Huntsville first: map WHNT, WAAY, WAFF, WZDX, WHDF to CBS, ABC, NBC, FOX, CW; rank them first in Local and in Games. S/M
5. Games matching parity with the TV: abbreviation pairs, one team plus "vs", guide titles near kickoff. M
6. Keep the web guide cache across app launches (15-minute TTL). S
7. TV guide: use the playlist's accepted user agent, normalized name matching, keep a trimmed guide on disk for cold starts. M
8. "On now" strip on Favorites and Sports, plus a Comedy shortcut. S/M
- Bug: a failed guide fetch is cached as empty for 15 minutes; retry after 1 to 2 minutes instead. S
- Relay (next batched deploy): block IPv6 private addresses and redirects to internal hosts in iptv.mts.

## Web and phone app (agent: web-app)

1. Fail fast when the TV is off: no 45-second waits; say "TV is off" and offer the Real-Debrid token path. S
2. iPhone tap-to-play: when autoplay is blocked, show a big Play button and start the 25-second watchdog only after it is tapped. S/M
3. Save the play position when the app goes to the background (iOS often skips pagehide). S
4. Network check in Settings: test TMDB, addons, the relay and ntfy, and say what is blocked (VPN, iCloud Private Relay). M
5. Safe updates: don't reload for a new version during Live TV, a source lookup, sign-in or the hand-off dialog; show "tap to update". S
6. Watchlist edits survive the TV being off (queue and resend). M
7. Service worker: 3-second timeout on the shell fetch; tie the cache name to BUILD. S
8. A global unhandled-error log so silent failures are visible. S
- Cleanup: `#/websites` is unreachable (remove or wire up); "Events & PPV" never occurs; duplicate helpers (b64u, sha, two visibilitychange listeners).

## TV app (agent: tv-app)

1. Turn on R8 and add a baseline profile: the biggest fix for lag on Fire sticks. M
2. "Try next source" when playback fails, instead of a dead end. M
3. Back returns to the row and tile you came from on Home. M
4. "Still watching?" after 3 automatic next episodes with no key press. S
5. Pause the Home hero carousel while it has focus. S
6. Smooth seeking: add up held LEFT/RIGHT presses and seek once. S
7. Memory: app-wide image loader with RGB565 and a bounded cache; buffer settings for 1 GB sticks. S/M
8. Move watch history out of SharedPreferences into files (it is rewritten every 15 s on the main thread). M
- Risks: HouseSync wakes every 4 s forever to compare settings; every launch sends a house sync request that every other TV answers; "TV started" goes to ntfy on every launch; keepScreenOn stays on while paused; leaving the update screen cancels the download silently.

## QA

- Web QA now runs on docs/ changes. Next: run the Kotlin unit tests as a fast job before the 40-minute emulator job; mock TMDB and ESPN in the emulator QA so a vendor outage can't fail it.
