#!/bin/bash
# McD TV automated QA (runs inside the Android emulator job). Results go to qa-out/.
mkdir -p qa-out
R=qa-out/results.txt
: > "$R"
pass() { echo "PASS  $1" | tee -a "$R"; }
fail() { echo "FAIL  $1" | tee -a "$R"; FAILED=1; }
shot() { adb exec-out screencap -p > "qa-out/$1.png"; }
FAILED=0

adb install -r app/build/outputs/apk/release/app-release.apk && pass "APK installs" || fail "APK installs"
adb logcat -c

# 1) Launch with intro, then home.
adb shell am start -n com.mcd.tv/.MainActivity --ez qa true --ez qa_mode true
sleep 4; shot 01-intro
sleep 12; shot 02-home
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml qa-out/ui-home.xml >/dev/null 2>&1
if grep -qiE "TRENDING THIS WEEK|PICKED FOR|BECAUSE YOU WATCHED" qa-out/ui-home.xml; then pass "Home loads TMDB rows"; else fail "Home loads TMDB rows"; fi

# 2) Setup web page: reachable, and saves what a browser sends.
adb forward tcp:8642 tcp:8642
if curl -sf -m 10 http://127.0.0.1:8642/ -o qa-out/setup-page.html && grep -q "Jarvis" qa-out/setup-page.html; then
  pass "Setup page loads (GET)"
else
  fail "Setup page loads (GET)"
fi
if curl -sf -m 15 --data "site=https%3A%2F%2Fexample.com&siteName=QA+Check" http://127.0.0.1:8642/ -o qa-out/setup-post.html \
   && grep -q "Website added: QA Check" qa-out/setup-post.html; then
  pass "Setup page saves a website (POST)"
else
  fail "Setup page saves a website (POST)"
fi

# 2b) Internet setup link (ntfy relay), end to end.
pip install -q cryptography >/dev/null 2>&1
LINK=$(curl -sf -m 10 http://127.0.0.1:8642/qa-relay)
if [ -n "$LINK" ]; then
  python3 .github/qa/relay_test.py "$LINK" || FAILED=1
else
  fail "Internet setup link available"
fi

# 3) Page still answers after the app goes to the background (screensaver case).
adb shell input keyevent KEYCODE_HOME
sleep 5
if curl -sf -m 10 http://127.0.0.1:8642/ -o /dev/null; then pass "Setup page survives app in background"; else fail "Setup page survives app in background"; fi

# 4) Screens open without crashing and show what they should (reads the on-screen text).
check_screen() { # name, expected text (case-insensitive regex)
  adb shell am start -S -n com.mcd.tv/.MainActivity --es screen "$1" >/dev/null
  sleep 9
  shot "screen-${1//:/-}"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "qa-out/ui-${1//:/-}.xml" >/dev/null 2>&1
  if grep -qiE "$2" "qa-out/ui-${1//:/-}.xml" 2>/dev/null; then pass "Screen $1 shows: $2"; else
    fail "Screen $1 shows: $2"
    echo "      on screen: $(grep -o 'text="[^"]*"' "qa-out/ui-${1//:/-}.xml" 2>/dev/null | sed 's/text=//' | tr '\n' ' ' | cut -c1-400)" | tee -a "$R"
  fi
}
check_screen settings "Check for updates"
check_screen settings:sources "Phone &amp; Computer Setup|Phone & Computer Setup"
check_screen phone "JARVIS CONTROL"
check_screen genres "All years"
check_screen services "Netflix"
check_screen sports "WEBSITES"
check_screen profiles "S WATCHING"
check_screen noise "BACKGROUND NOISE"
check_screen library "Real-Debrid Cloud"
check_screen live "playlist"
check_screen multiview "MULTIVIEW"
# Games hub (no playlist yet): the league header shows before the ESPN schedule loads, and even if it fails.
check_screen games "NFL"
check_screen search "Search movies"
check_screen ask "ASK JARVIS"
check_screen browse "Collections"
check_screen "detail:movie:603" "Matrix"
check_screen "detail:movie:603" "TMDB"
check_screen "person:6384" "Keanu"

# 4a) Settings with the remote: a screenshot of each category, a D-pad walk through its rows (the keyboard must
# never open on focus), the edit dialog (opens on OK, one BACK closes it) and the update check in two presses.
kb_shown() { adb shell dumpsys input_method | grep -q "mInputShown=true"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "qa-out/$1.xml" >/dev/null 2>&1; }
# Writes what has focus and the visible text to the log (screenshots are in the artifact; this is for log readers).
describe() {
  dump "ui-$1"
  echo "      [$1] focused: $(grep -o '<node [^>]*focused="true"[^>]*>' "qa-out/ui-$1.xml" 2>/dev/null | grep -o 'text="[^"]*"\|content-desc="[^"]*"\|bounds="[^"]*"' | tr '\n' ' ' | cut -c1-300)"
  echo "      [$1] focused holds: $(python3 .github/qa/focused.py "qa-out/ui-$1.xml" | cut -c1-300)"
  echo "      [$1] text: $(grep -o 'text="[^"]\+"' "qa-out/ui-$1.xml" 2>/dev/null | sed 's/text=//' | tr '\n' ' ' | cut -c1-700)"
}
KB_FAIL=""
NAV_FAIL=""
FIRST=1
for cat in tv updates profiles playback sources wall network about; do
  adb shell am start -S -n com.mcd.tv/.MainActivity --es screen "settings:$cat" >/dev/null
  sleep 8
  shot "settings-$cat"
  describe "settings-$cat"
  # The first cold launch after the other screens: look again a little later (focus timing on a cold start).
  if [ -n "$FIRST" ]; then sleep 4; describe "settings-$cat-later"; FIRST=""; fi
  adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
  describe "settings-$cat-right"
  grep -q '<node [^>]*focused="true"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,\([2-9][0-9][0-9]\|1[0-9][0-9][0-9]\)\]' "qa-out/ui-settings-$cat-right.xml" || NAV_FAIL="$NAV_FAIL $cat"
  for i in $(seq 1 10); do
    adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1
    kb_shown && KB_FAIL="$KB_FAIL $cat:$i"
  done
  shot "settings-$cat-walked"
  describe "settings-$cat-walked"
done
# RIGHT from the rail must stay in the page, never jump to the top bar (focus starts on the rail).
if [ -z "$NAV_FAIL" ]; then pass "Settings: opens with focus in the page"; else fail "Settings: opens with focus in the page (top bar or nothing at:$NAV_FAIL)"; fi
if [ -z "$KB_FAIL" ]; then pass "Settings: D-pad never opens the keyboard"; else fail "Settings: D-pad never opens the keyboard (opened at$KB_FAIL)"; fi

# Fresh open lands on App updates in the rail: RIGHT reaches Check for updates, OK checks.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen settings >/dev/null
sleep 9
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 6
shot settings-updates-checked
describe settings-updates-checked
if grep -qiE "up to date|is ready|Checking|t check" qa-out/ui-settings-updates-checked.xml && grep -qi "Last checked" qa-out/ui-settings-updates-checked.xml; then
  pass "Settings: update check in two presses"
else
  fail "Settings: update check in two presses"
fi

# Edit row: Profiles > RIGHT (Watching now) > DOWN (Profile 1) > OK opens the rename dialog; BACK closes it.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen settings:profiles >/dev/null
sleep 8
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1
kb_shown && fail "Settings: no keyboard on an edit row before OK" || pass "Settings: no keyboard on an edit row before OK"
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 3
shot settings-edit-open
describe settings-edit-open
grep -q "Rename" qa-out/ui-settings-edit-open.xml && pass "Settings: OK on an edit row opens the editor" || fail "Settings: OK on an edit row opens the editor"
kb_shown && echo "      keyboard is open in the editor" | tee -a "$R"
adb shell input keyevent KEYCODE_BACK; sleep 2
shot settings-edit-closed
describe settings-edit-closed
if ! grep -q "Rename" qa-out/ui-settings-edit-closed.xml && grep -q "Profile 1" qa-out/ui-settings-edit-closed.xml && ! kb_shown; then
  pass "Settings: one BACK closes the keyboard and the editor"
else
  fail "Settings: one BACK closes the keyboard and the editor"
fi

# 4b) Live TV with a sample M3U playlist served from the test machine (emulator sees it at 10.0.2.2).
# The playlist has an Entertainment block where most networks come in several feeds (make_m3u.py), and 7 channels
# are starred through the relay first, like J's 7 favorites.
mkdir -p qa-m3u
python3 .github/qa/make_m3u.py qa-m3u/test.m3u qa-m3u/favs.txt | tee -a "$R"
python3 -m http.server 8765 --directory qa-m3u >/dev/null 2>&1 < /dev/null &
M3U_SERVER=$!
sleep 2
curl -sf -m 15 --data "m3u=http%3A%2F%2F10.0.2.2%3A8765%2Ftest.m3u" http://127.0.0.1:8642/ -o /dev/null \
  && pass "Setup page saves an M3U playlist" || fail "Setup page saves an M3U playlist"
sleep 3 # let Android write the setting to disk before the app is restarted
check_screen live "QA Test Channel|Live Games|NFL"
if grep -q "QA Movie Entry" qa-out/ui-live.xml 2>/dev/null; then fail "Live TV hides movie entries"; else pass "Live TV hides movie entries"; fi
LINK=$(curl -sf -m 10 http://127.0.0.1:8642/qa-relay)
[ -n "$LINK" ] && python3 .github/qa/live_favs.py "$LINK" qa-m3u/favs.txt | tee -a "$R"
sleep 15

# (a) A fresh open of Live TV lands at the top: focus on the NFL Games chip inside Live Games, with favorites saved.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen live >/dev/null
sleep 12
shot live-a-landing
describe live-a-landing
python3 .github/qa/focused.py qa-out/ui-live-a-landing.xml | grep -q 'NFL GAMES' \
  && pass "Live TV: opens with focus on NFL Games" || fail "Live TV: opens with focus on NFL Games"
grep -qE 'text="[^"]*Favorites[^"]*7[^"]*"|text="7"' qa-out/ui-live-a-landing.xml \
  && pass "Live TV: 7 favorites saved through the relay" || fail "Live TV: 7 favorites saved through the relay"
# (b) Live Games is one rail entry; the leagues are chips inside it. WNBA and soccer are hidden by default.
if grep -q 'Live Games' qa-out/ui-live-a-landing.xml && ! grep -qiE 'text="[^"]*(WNBA|MLS|PREMIER LEAGUE)' qa-out/ui-live-a-landing.xml; then
  pass "Live TV: Live Games holds the leagues, no WNBA or soccer"
else
  fail "Live TV: Live Games holds the leagues, no WNBA or soccer"
fi
# D-pad: one DOWN reaches the first game (live, else the next one). OK would play it.
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 2
shot live-a-first-game
describe live-a-first-game
# The focused card holds a team name or a time, right of the rail (x > 460) and below the league chips (y > 300 of 1080).
GAME_F=$(python3 .github/qa/focused.py qa-out/ui-live-a-first-game.xml)
echo "$GAME_F" | grep -qE '@\[([5-9][0-9][0-9]|4[6-9][0-9]|1[0-9][0-9][0-9]),([3-9][0-9][0-9]|1[0-9][0-9][0-9])\]' && ! echo "$GAME_F" | grep -q 'NFL GAMES' \
  && pass "Live TV: DOWN from NFL Games reaches a game (2 presses to play)" || fail "Live TV: DOWN from NFL Games reaches a game"
shot live-b-games
# Dad's favorite teams (Eagles, Phillies, Flyers, 76ers by default) go first in their league, with a star.
grep -q 'YOUR TEAMS' qa-out/ui-live-a-landing.xml && echo "      NFL: an Eagles game is pinned under YOUR TEAMS" | tee -a "$R"

# (d) My Teams: the chip left of NFL Games collects favorite-team games across leagues. A fresh open focuses
# NFL Games; LEFT is My Teams, OK opens it.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen live >/dev/null
sleep 12
adb shell input keyevent KEYCODE_DPAD_LEFT; sleep 1
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 3
shot live-d-myteams
describe live-d-myteams
if grep -q 'text="MY TEAMS"' qa-out/ui-live-d-myteams.xml && grep -q 'MY TEAM"' qa-out/ui-live-d-myteams.xml; then
  pass "Live TV: My Teams lists favorite-team games with a star ($(grep -oE 'text="[^"]*(Eagles|Phillies|Flyers|76ers)"' qa-out/ui-live-d-myteams.xml | sort -u | tr '\n' ' '))"
else
  fail "Live TV: My Teams lists favorite-team games with a star"
fi

# (c) Entertainment: one row per network. BACK returns to Live Games in the rail; then Search, Favorites, My Guide,
# Sports, Entertainment.
adb shell input keyevent KEYCODE_BACK; sleep 1
for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1; done
shot live-c-entertainment
describe live-c-entertainment
grep -qE 'text="[0-9,]+ networks  ·  [0-9,]+ channels"' qa-out/ui-live-c-entertainment.xml \
  && pass "Live TV: Entertainment lists networks ($(grep -oE '[0-9,]+ networks  ·  [0-9,]+ channels' qa-out/ui-live-c-entertainment.xml | head -1))" \
  || fail "Live TV: Entertainment lists networks"
grep -q 'text="★ YOURS' qa-out/ui-live-c-entertainment.xml && pass "Live TV: Entertainment pins your networks" || fail "Live TV: Entertainment pins your networks"
# RIGHT into the list (the first pinned network), RIGHT again opens its other feeds under it.
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 2
shot live-c-feeds
describe live-c-feeds
grep -q 'Hide the other feeds' qa-out/ui-live-c-feeds.xml && pass "Live TV: RIGHT opens a network's other feeds" || fail "Live TV: RIGHT opens a network's other feeds"

# Play the first favorite with the remote and make sure the player opens: Live TV opens on NFL Games; BACK to the
# rail (Live Games), DOWN x2 to Favorites, RIGHT to the first card (QA Test Channel), CENTER plays.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen live >/dev/null
sleep 12
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
describe live-play-target
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 10
shot live-playing
adb shell dumpsys activity activities | grep -q "com.mcd.tv" && pass "Live channel opens the player without leaving the app" || fail "Live channel opens the player without leaving the app"

# Settings > Addons and Live TV > My teams: Dad's four Philadelphia teams (rows after the 8 league switches).
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen settings:sources >/dev/null
sleep 8
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
for i in $(seq 1 12); do adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1; done
shot settings-myteams
describe settings-myteams
MISSING=""
for t in Eagles Phillies Flyers 76ers; do grep -q "text=\"[^\"]*  $t\"" qa-out/ui-settings-myteams.xml || MISSING="$MISSING $t"; done
[ -z "$MISSING" ] && pass "Settings: My teams lists Eagles, Phillies, Flyers and 76ers" || fail "Settings: My teams lists the Philadelphia teams (missing:$MISSING)"

# 4c) Remote walk-through: title page, Play, back out.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen "detail:movie:603" >/dev/null
sleep 9
# Main row is Play, Trailer (if any), Watchlist, "More": RIGHT x3 lands on More (a spare RIGHT is harmless).
# More opens the second row and focuses its first button, Choose Source.
for i in 1 2 3; do adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1; done
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 2
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 6
shot remote-choose-source
for i in 1 2 3; do adb shell input keyevent KEYCODE_BACK; sleep 2; done
shot remote-after-back

# 5) Crash check.
adb logcat -d > qa-out/logcat.txt
if grep -q "ANR in com.mcd.tv" qa-out/logcat.txt; then fail "No freezes (ANR)"; grep -A 15 "ANR in com.mcd.tv" qa-out/logcat.txt > qa-out/anr.txt; else pass "No freezes (ANR)"; fi
if grep -q "FATAL EXCEPTION" qa-out/logcat.txt; then
  fail "No crashes"
  grep -A 25 "FATAL EXCEPTION" qa-out/logcat.txt > qa-out/crash.txt
else
  pass "No crashes"
fi

kill "$M3U_SERVER" 2>/dev/null # a server left running keeps the job from finishing
echo "----"; cat "$R"
if [ -n "$GITHUB_STEP_SUMMARY" ]; then { echo "## McD TV QA"; echo '```'; cat "$R"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"; fi
exit $FAILED
