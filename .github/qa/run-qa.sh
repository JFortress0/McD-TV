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
if grep -qi "TRENDING THIS WEEK" qa-out/ui-home.xml; then pass "Home loads TMDB rows"; else fail "Home loads TMDB rows"; fi

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
check_screen settings "Phone &amp; Computer Setup|Phone & Computer Setup"
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
