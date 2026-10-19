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
