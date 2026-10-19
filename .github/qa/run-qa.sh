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

# 4v) Wall rotation end to end: a mock wall server (photos and a feed), the TV set up through the encrypted relay
# like the Control page does it, and the clock set just before a calendar minute. With the default wall_cycle
# "30/1" the Wall shows photos, then the month calendar for one minute (at :29), then photos again.
pip install -q pillow >/dev/null 2>&1
python3 .github/qa/wall_mock.py 8766 >/dev/null 2>&1 < /dev/null &
WALL_SERVER=$!
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen settings >/dev/null
sleep 8
LINK=$(curl -sf -m 10 http://127.0.0.1:8642/qa-relay)
python3 .github/qa/relay_send.py "$LINK" '{"cmd":"set","data":{"wall_url":"http://10.0.2.2:8766","wall_key":"qa-wall-key","wall_mode":"","wall_cycle":"30/1"}}' | tee -a "$R" \
  && pass "Wall: set up through the relay (address, key, default timing)" || fail "Wall: set up through the relay (address, key, default timing)"
sleep 3
# Root shell to set the clock (the emulator image allows it). Automatic time off, so it stays where we put it.
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
adb shell settings put global auto_time 0
adb shell setprop persist.sys.timezone UTC
adb shell date "$(date -u +%m%d)1028$(date -u +%Y).30" >/dev/null
echo "      device clock set to: $(adb shell date)" | tee -a "$R"
adb shell am start -S -n com.mcd.tv/.MainActivity --es screen wall >/dev/null
view_now() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "qa-out/ui-$1.xml" >/dev/null 2>&1
  if grep -q 'text="SUN"' "qa-out/ui-$1.xml" && grep -q 'text="SAT"' "qa-out/ui-$1.xml"; then echo month; else echo photos; fi; }
sleep 12                     # about 10:28:42: photos
shot wall-cycle-1-1028; V1=$(view_now wall-cycle-1)
sleep 25                     # about 10:29:07: the month calendar's one minute
shot wall-cycle-2-1029; V2=$(view_now wall-cycle-2)
sleep 58                     # about 10:30:05: photos again
shot wall-cycle-3-1030; V3=$(view_now wall-cycle-3)
echo "      wall views: 10:28 $V1, 10:29 $V2, 10:30 $V3 (device clock now $(adb shell date +%H:%M:%S))" | tee -a "$R"
[ "$V1" = photos ] && [ "$V2" = month ] && [ "$V3" = photos ] \
  && pass "Wall rotation: photos, then the calendar for its one minute, then photos" \
  || fail "Wall rotation: photos, then the calendar for its one minute, then photos ($V1 $V2 $V3)"
grep -q "Soccer practice" qa-out/ui-wall-cycle-1.xml && pass "Wall: agenda shows the feed beside the photo" || fail "Wall: agenda shows the feed beside the photo"
python3 .github/qa/thumbs.py qa-out/wall-cycle-1-1028.png qa-out/wall-cycle-2-1029.png qa-out/wall-cycle-3-1030.png
# Put the clock back for the rest of the run.
adb shell date "$(date -u +%m%d%H%M%Y.%S)" >/dev/null
adb shell settings put global auto_time 1
adb forward tcp:8642 tcp:8642 >/dev/null  # adb root restarted adbd, which dropped the forward the next steps use
kill "$WALL_SERVER" 2>/dev/null

# 4b) Live TV with a sample M3U playlist served from the test machine (emulator sees it at 10.0.2.2).
mkdir -p qa-m3u
cat > qa-m3u/test.m3u <<'M3U'
#EXTM3U
#EXTINF:-1 tvg-logo="" group-title="QA Sports",QA Test Channel
https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8
#EXTINF:-1 group-title="QA Movies",QA Movie Entry
http://example.com/movie/user/pass/1.mp4
M3U
python3 -m http.server 8765 --directory qa-m3u >/dev/null 2>&1 < /dev/null &
M3U_SERVER=$!
sleep 2
curl -sf -m 15 --data "m3u=http%3A%2F%2F10.0.2.2%3A8765%2Ftest.m3u" http://127.0.0.1:8642/ -o /dev/null \
  && pass "Setup page saves an M3U playlist" || fail "Setup page saves an M3U playlist"
sleep 3 # let Android write the setting to disk before the app is restarted
check_screen live "QA Test Channel"
if grep -q "QA Movie Entry" qa-out/ui-live.xml 2>/dev/null; then fail "Live TV hides movie entries"; else pass "Live TV hides movie entries"; fi
# Play the first channel with the remote and make sure the player opens.
# With no addon live catalogs there is no source row. Once the playlist loads, focus sits on the selected
# entry of the left rail: with no favorites or recents that is the first section ("Sports": the QA channel is
# in group "QA Sports"). RIGHT -> first channel card in the grid, CENTER -> play.
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 10
shot live-playing
adb shell dumpsys activity activities | grep -q "com.mcd.tv" && pass "Live channel opens the player without leaving the app" || fail "Live channel opens the player without leaving the app"

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
