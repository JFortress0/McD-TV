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
adb shell am start -n com.mcd.tv/.MainActivity
sleep 4; shot 01-intro
sleep 10; shot 02-home

# 2) Setup web page: reachable, and saves what a browser sends.
adb forward tcp:8642 tcp:8642
if curl -sf -m 10 http://127.0.0.1:8642/ -o qa-out/setup-page.html && grep -q "McD" qa-out/setup-page.html; then
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

# 3) Page still answers after the app goes to the background (screensaver case).
adb shell input keyevent KEYCODE_HOME
sleep 5
if curl -sf -m 10 http://127.0.0.1:8642/ -o /dev/null; then pass "Setup page survives app in background"; else fail "Setup page survives app in background"; fi

# 4) Screens open without crashing (debug extra opens a screen directly).
for s in phone settings genres sports noise library live services search; do
  adb shell am start -S -n com.mcd.tv/.MainActivity --es screen "$s" >/dev/null
  sleep 7
  shot "screen-$s"
done

# 5) Crash check.
adb logcat -d > qa-out/logcat.txt
if grep -q "FATAL EXCEPTION" qa-out/logcat.txt; then
  fail "No crashes"
  grep -A 25 "FATAL EXCEPTION" qa-out/logcat.txt > qa-out/crash.txt
else
  pass "No crashes"
fi

echo "----"; cat "$R"
exit $FAILED
