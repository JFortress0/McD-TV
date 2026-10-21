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

# 4-history) History hub on the QA profile (Dad). First mark Ken Burns' The Civil War watched (QA-only extra), so the
# personal row is seeded from real history (inner quotes: adb runs this through the device shell, where | is a pipe); then the hub, its Ken Burns grid and two era grids.
# Poster names are in content-desc; rail headings are upper case text.
adb shell am start -S -n com.mcd.tv/.MainActivity --ez qa_mode true --es qa_watched "'tv|The Civil War|1990'" --es screen history >/dev/null
sleep 25 # first visit: marquee, keyword and era lookups
check_screen history "KEN BURNS"
sleep 25; shot screen-history-loaded
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml qa-out/ui-history-loaded.xml >/dev/null 2>&1
# For log readers (artifacts need a download): the hub's headings and the History log lines.
echo "      [history] text: $(grep -o 'text="[^"]\+"' qa-out/ui-history-loaded.xml 2>/dev/null | sed 's/text=//' | tr '\n' ' ' | cut -c1-700)"
echo "      [history] posters: $(grep -o 'content-desc="[^"]\+"' qa-out/ui-history-loaded.xml 2>/dev/null | sed 's/content-desc=//' | tr '\n' ' ' | cut -c1-700)"
adb logcat -d -s HistoryHub:I | grep -v "^---" | tail -n 20 | sed 's/^/      [history] log: /'
if grep -q "MORE LIKE THE CIVIL WAR" qa-out/ui-history-loaded.xml; then pass "History: More Like row seeded from a watched history title"; else fail "History: More Like row seeded from a watched history title"; fi
# The More Like row sits on top and the eras the profile watches move up, so look for the Historical Dramas
# heading after each press of DOWN.
for i in 1 2 3 4 5; do
  adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 3
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "qa-out/ui-history-down$i.xml" >/dev/null 2>&1
  [ "$i" = 2 ] && shot screen-history-scrolled
done
sleep 2; shot screen-history-eras
echo "      [history-scrolled] headings: $(cat qa-out/ui-history-down*.xml 2>/dev/null | grep -o 'text="[A-Z][A-Z &amp;;IVX]\{6,\}"' | sort -u | sed 's/text=//' | tr '\n' ' ' | cut -c1-500)"
if grep -q "HISTORICAL DRAMAS" qa-out/ui-history-loaded.xml qa-out/ui-history-down*.xml; then pass "History: Historical Dramas shelf"; else fail "History: Historical Dramas shelf"; fi
check_screen history:kenburns "content-desc=\"The American Revolution\""
if grep -q 'content-desc="The Civil War"' qa-out/ui-history-kenburns.xml; then pass "History: Ken Burns shows The Civil War"; else fail "History: Ken Burns shows The Civil War"; fi
check_screen history:era:civilwar "content-desc=\"(Lincoln|Glory|Gettysburg)\""
check_screen history:era:founding "content-desc=\"(Young Washington|John Adams|The Patriot)\""
check_screen history:dramas "content-desc=\"Young Washington\""
check_screen history:era:worldwars "content-desc="
check_screen history:era:coldwar "content-desc="
check_screen history:era:west "content-desc="
adb logcat -d -s HistoryHub:I | grep "era \|seed" | tail -n 12 | sed 's/^/      [history] log: /'
for g in kenburns era-founding era-civilwar era-worldwars era-coldwar era-west dramas; do
  echo "      [history-$g] posters: $(grep -o 'content-desc="[^"]\+"' "qa-out/ui-history-$g.xml" 2>/dev/null | sed 's/content-desc=//' | tr '\n' ' ' | cut -c1-600)"
done

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

# 4w) The Wall keeps the screen on past the idle timeout, also after an HDMI-style density change and after Android
# rebuilds the activity. A screen with no hold must go to sleep first (control), so this test can fail.
# Soak protocol for a real Fire TV: tools/soak/WALL_SOAK.md.
wake() { adb shell dumpsys power | grep -o 'mWakefulness=[A-Za-z]*' | head -1 | cut -d= -f2; }
keep_flag() { adb shell dumpsys window windows | grep -A40 'com.mcd.tv/com.mcd.tv.MainActivity' | grep -q KEEP_SCREEN_ON; }
wall_shown() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml qa-out/ui-wall-now.xml >/dev/null 2>&1; grep -q "JARVIS WALL\|Not set up yet" qa-out/ui-wall-now.xml; }
adb shell settings put system screen_off_timeout 15000
# Emulators often stay awake while "plugged in": turn that off so the idle timeout can act.
adb shell settings put global stay_on_while_plugged_in 0
adb shell svc power stayon false
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen browse >/dev/null
sleep 35
CTRL=$(wake); echo "      control (Browse, no hold) after 35 s: $CTRL" | tee -a "$R"
[ "$CTRL" != "Awake" ] && pass "Wall test control: a screen with no hold goes to sleep" || fail "Wall test control: a screen with no hold goes to sleep (still $CTRL)"
adb shell input keyevent KEYCODE_WAKEUP; sleep 2
adb logcat -c
# Open the Wall the way a person does (Settings > Jarvis Wall > Open Wall), not with "--es screen wall": a rebuilt
# activity gets its launch intent again, so a deep link would bring the Wall back even without the fix.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen settings:wall >/dev/null
sleep 8
# Two RIGHTs: the first may only end touch mode (focus then goes to the rail), the second reaches Open Wall.
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 6
wall_shown && pass "Wall: opens from Settings" || fail "Wall: opens from Settings"
keep_flag && pass "Wall: window has FLAG_KEEP_SCREEN_ON" || fail "Wall: window has FLAG_KEEP_SCREEN_ON"
sleep 30
W=$(wake); shot wall-idle
[ "$W" = "Awake" ] && pass "Wall: screen stays on past the idle timeout" || fail "Wall: screen stays on past the idle timeout ($W)"
# An HDMI mode change can change the display density. The Wall must stay up and held, with no rebuild.
adb shell wm density 240; sleep 6
if wall_shown && keep_flag; then pass "Wall: stays up and held after a density change"; else fail "Wall: stays up and held after a density change"; fi
adb shell wm density reset; sleep 4
# Android rebuilds the activity: HOME, then the system kills the app in the background (as low memory does), then
# it is opened again. Android restores the task from saved state, with its first launch intent (Settings) again:
# the Wall must win. ("settings put global always_finish_activities 1" does not take effect without the
# Developer options screen, so it is not used.)
PID_BEFORE=$(adb shell pidof com.mcd.tv | tr -d '\r')
adb shell input keyevent KEYCODE_HOME; sleep 4
adb shell am kill com.mcd.tv; sleep 2
adb shell am start -n com.mcd.tv/.MainActivity >/dev/null; sleep 10
PID_AFTER=$(adb shell pidof com.mcd.tv | tr -d '\r')
echo "      app process before $PID_BEFORE, after $PID_AFTER" | tee -a "$R"
[ -n "$PID_AFTER" ] && [ "$PID_BEFORE" != "$PID_AFTER" ] && pass "Wall: the app process was really replaced" || fail "Wall: the app process was really replaced"
shot wall-rebuilt
if wall_shown && keep_flag; then pass "Wall: comes back held after the activity is rebuilt"; else fail "Wall: comes back held after the activity is rebuilt"; fi
sleep 30
W=$(wake); [ "$W" = "Awake" ] && pass "Wall: still awake after the rebuild" || fail "Wall: still awake after the rebuild ($W)"
# The guard's log: the rebuild is noted, and the self-check found no lapse while Jarvis was in front.
adb logcat -d -s JarvisAwake:I | sed 's/^/      awake: /' | tee -a "$R"
adb logcat -d -s JarvisAwake:I | grep -q "Wall reopened after the app was rebuilt" && pass "Wall: rebuild logged" || fail "Wall: rebuild logged"
adb logcat -d -s JarvisAwake:I | grep -q "lapsed" && fail "Wall: no keep-screen-on lapse" || pass "Wall: no keep-screen-on lapse"
adb shell settings put system screen_off_timeout 2147483647

# 4x) Why the Wall stopped (WallWatch): the paths keep-screen-on cannot cover, and the evidence Jarvis leaves.
# (a) Inactivity sleep: Android TV's attentive_timeout (Android 10+, like Fire OS 8) puts the TV to sleep after a
# time with no remote use, even while a window holds keep-screen-on. Jarvis must warn about it and log the sleep.
adb shell input keyevent KEYCODE_WAKEUP; sleep 2
adb logcat -c
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen wall >/dev/null; sleep 8
keep_flag && pass "Wall 4x: window has FLAG_KEEP_SCREEN_ON before the inactivity test" || fail "Wall 4x: window has FLAG_KEEP_SCREEN_ON before the inactivity test"
# A remote key now (RIGHT: next photo), then a 20 s inactivity sleep. Awake at 5 s, asleep at 45 s, Wall held.
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell settings put secure attentive_timeout 20000
sleep 5
W=$(wake); echo "      wakefulness 5 s after the key, attentive_timeout=20000, Wall held: $W" | tee -a "$R"
[ "$W" = "Awake" ] && pass "Wall 4x: awake and held right after a key" || fail "Wall 4x: awake and held right after a key ($W)"
sleep 40
W=$(wake); echo "      wakefulness 45 s after the key with the Wall held: $W" | tee -a "$R"
[ "$W" != "Awake" ] && pass "Inactivity sleep (attentive_timeout) overrides keep-screen-on" || fail "Inactivity sleep (attentive_timeout) overrides keep-screen-on (still $W)"
adb shell settings delete secure attentive_timeout >/dev/null
adb shell input keyevent KEYCODE_WAKEUP; sleep 4
adb logcat -d -s JarvisAwake:I | sed 's/^/      awake: /' | tee -a "$R"
adb logcat -d -s JarvisAwake:I | grep -q "attentive_timeout=20000" && pass "Wall 4x: sleep settings logged" || fail "Wall 4x: sleep settings logged"
adb logcat -d -s JarvisAwake:I | grep -q "The TV went to sleep while the Wall was up" && pass "Wall 4x: sleep noted" || fail "Wall 4x: sleep noted"
adb logcat -d -s JarvisAwake:I | grep "The TV went to sleep" | grep -q "Cause: this TV sleeps after 20 s with no remote use (attentive_timeout)" && pass "Wall 4x: the sleep note names attentive_timeout" || fail "Wall 4x: the sleep note names attentive_timeout"
# The warning when the Wall opens with attentive_timeout already on (the usual case on a TV).
adb logcat -c
adb shell settings put secure attentive_timeout 14400000
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen wall >/dev/null; sleep 8
adb logcat -d -s JarvisAwake:I | grep -q "turns itself off after 4 h 0 min" && pass "Wall 4x: inactivity sleep warning when the Wall opens" || fail "Wall 4x: inactivity sleep warning when the Wall opens"
adb shell settings delete secure attentive_timeout >/dev/null
# (b) Jarvis ended while the Wall is in front (as a crash or a low-memory kill would): opening Jarvis again, with no
# deep link, brings the Wall back, and the log says why it stopped.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen wall >/dev/null; sleep 8
adb logcat -c
adb shell am force-stop com.mcd.tv; sleep 2
# "--ez qa true" is not a screen deep link (the Wall must come back by itself); it starts the setup page for 4b.
adb shell am start -n com.mcd.tv/.MainActivity --ez qa true >/dev/null; sleep 10
shot wall-ended-in-front
if wall_shown && keep_flag; then pass "Wall 4x: back and held after Jarvis ended in front"; else fail "Wall 4x: back and held after Jarvis ended in front"; fi
adb logcat -d -s JarvisAwake:I | sed 's/^/      awake: /' | tee -a "$R"
adb logcat -d -s JarvisAwake:I | grep -q "The Wall stopped" && pass "Wall 4x: why it stopped is logged" || fail "Wall 4x: why it stopped is logged"
adb logcat -d -s JarvisAwake:I | grep -q "Wall reopened after Jarvis ended while it was in front" && pass "Wall 4x: reopen logged" || fail "Wall 4x: reopen logged"

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

# (a) A fresh open of Live TV lands on What's On: rows from the guide (guide.xml from make_m3u.py, built around this
# emulator's clock), focus on the first tile, which is the live Eagles game pinned in My Teams.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen live >/dev/null
sleep 20
shot live-a-landing
describe live-a-landing
L=qa-out/ui-live-a-landing.xml
MISSING=""
for r in "MY TEAMS" "LIVE GAMES" "HISTORY &amp; DOCS ON NOW" "MILITARY ON NOW" "CYBER, AI &amp; TECH ON NOW" "PRIMETIME ENTERTAINMENT"; do grep -q "text=\"$r\"" "$L" || MISSING="$MISSING [$r]"; done
[ -z "$MISSING" ] && pass "Live TV: What's On shows its six rows" || fail "Live TV: What's On rows missing:$MISSING"
FOC=$(python3 .github/qa/focused.py "$L")
echo "      focused: $FOC" | tee -a "$R"
echo "$FOC" | grep -q "Eagles" && echo "$FOC" | grep -qi "MY TEAM" \
  && pass "Live TV: opens on the live Eagles game, pinned first in My Teams" || fail "Live TV: opens on the live Eagles game, pinned first in My Teams"
grep -q "Inter Miami" "$L" && fail "Live TV: an MLS game shows in Live Games" || pass "Live TV: no MLS or WNBA game in Live Games"
grep -q 'All Channels A–Z' "$L" && pass "Live TV: All Channels A–Z is one entry" || fail "Live TV: All Channels A–Z is one entry"
grep -qE 'text="(Sports|Entertainment)"' "$L" && fail "Live TV: the old Sports or Entertainment section is still in the rail" || pass "Live TV: no Sports or Entertainment section in the rail"
grep -qE 'text="[^"]*Favorites[^"]*7[^"]*"|text="7"' "$L" \
  && pass "Live TV: 7 favorites saved through the relay" || fail "Live TV: 7 favorites saved through the relay"

# (b) OK plays the Eagles game on Philadelphia's FOX station; DOWN switches to the network's next feed.
# The channel overlay shows for 4 s after the player opens: read it inside that time.
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 2
describe live-b-player
shot live-b-player
grep -q "WTXF Philadelphia" qa-out/ui-live-b-player.xml && grep -q "feed 1 of" qa-out/ui-live-b-player.xml \
  && pass "Live TV: the Eagles tile plays the Philadelphia station, feed 1 of $(grep -oE 'feed 1 of [0-9]+' qa-out/ui-live-b-player.xml | head -1 | grep -oE '[0-9]+$')" \
  || fail "Live TV: the Eagles tile plays the Philadelphia station first"
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 2
describe live-b-feed2
grep -q "feed 2 of" qa-out/ui-live-b-feed2.xml && pass "Live TV: DOWN in the player switches to the next feed" || fail "Live TV: DOWN in the player switches to the next feed"
adb shell input keyevent KEYCODE_BACK; sleep 3
describe live-b-back
python3 .github/qa/focused.py qa-out/ui-live-b-back.xml | grep -q "Eagles" && pass "Live TV: Back from the player returns to the tile" || fail "Live TV: Back from the player returns to the tile"

# (c) All Channels A-Z: the last rail entry. One row per network; RIGHT on a row opens its other feeds.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen live >/dev/null
sleep 20
adb shell input keyevent KEYCODE_BACK; sleep 1
for i in 1 2 3 4 5 6 7 8; do adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1; done
shot live-c-az
describe live-c-az
grep -qE 'text="[0-9,]+ networks  ·  [0-9,]+ channels"' qa-out/ui-live-c-az.xml \
  && pass "Live TV: All Channels A–Z lists networks ($(grep -oE '[0-9,]+ networks  ·  [0-9,]+ channels' qa-out/ui-live-c-az.xml | head -1))" \
  || fail "Live TV: All Channels A–Z lists networks"
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 2
shot live-c-feeds
describe live-c-feeds
grep -q 'Hide the other feeds' qa-out/ui-live-c-feeds.xml && pass "Live TV: RIGHT opens a network's other feeds" || fail "Live TV: RIGHT opens a network's other feeds"

# (d) Schedules: the leagues' weeks, second in the rail. No WNBA or soccer chips by default.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen live >/dev/null
sleep 15
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 3
shot live-d-schedules
describe live-d-schedules
if grep -q 'Schedules' qa-out/ui-live-d-schedules.xml && ! grep -qiE 'text="[^"]*(WNBA|MLS|PREMIER LEAGUE)' qa-out/ui-live-d-schedules.xml; then
  pass "Live TV: Schedules holds the leagues, no WNBA or soccer"
else
  fail "Live TV: Schedules holds the leagues, no WNBA or soccer"
fi

# Play the first favorite with the remote: BACK to the rail (What's On), DOWN x3 to Favorites (Schedules, Search,
# Favorites), RIGHT to the first card (QA Test Channel), CENTER plays.
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen live >/dev/null
sleep 15
adb shell input keyevent KEYCODE_BACK; sleep 1
for i in 1 2 3; do adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 1; done
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

# 4d) Fresh install: app data cleared, the playlist sent again through the setup page, nothing else set (no favorites,
# no recents, no teams chosen). Live TV must show the same What's On rows and pin the Eagles game for Dad (p1).
adb shell pm clear com.mcd.tv >/dev/null
adb shell am start -n com.mcd.tv/.MainActivity --ez qa true >/dev/null; sleep 12
curl -sf -m 15 --data "m3u=http%3A%2F%2F10.0.2.2%3A8765%2Ftest.m3u" http://127.0.0.1:8642/ -o /dev/null \
  && pass "Fresh install: setup page saves the playlist" || fail "Fresh install: setup page saves the playlist"
sleep 3
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen live >/dev/null
sleep 20
shot live-fresh
describe live-fresh
MISSING=""
for r in "MY TEAMS" "LIVE GAMES" "HISTORY &amp; DOCS ON NOW" "MILITARY ON NOW" "CYBER, AI &amp; TECH ON NOW" "PRIMETIME ENTERTAINMENT"; do grep -q "text=\"$r\"" qa-out/ui-live-fresh.xml || MISSING="$MISSING [$r]"; done
[ -z "$MISSING" ] && pass "Fresh install: What's On shows the same six rows" || fail "Fresh install: What's On rows missing:$MISSING"
python3 .github/qa/focused.py qa-out/ui-live-fresh.xml | grep -q "Eagles" && pass "Fresh install: the live Eagles game is pinned first" || fail "Fresh install: the live Eagles game is pinned first"

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
