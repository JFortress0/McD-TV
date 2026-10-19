#!/usr/bin/env bash
# Wall soak probe: samples the Fire TV over adb and checks that Jarvis Wall keeps the screen on.
# Protocol and pass rule: tools/soak/WALL_SOAK.md.
#
# Usage:  tools/soak/wall-soak.sh <minutes> [interval-seconds]     (device already connected: adb connect <ip>)
# Writes one line per sample to stdout and to wall-soak-<date>.log. Exit code 0 = every sample passed.
set -uo pipefail

MINUTES="${1:?minutes}"
EVERY="${2:-60}"
PKG="com.mcd.tv"
LOG="wall-soak-$(date +%Y%m%d-%H%M).log"
END=$(( $(date +%s) + MINUTES * 60 ))
fails=0
samples=0

say() { echo "$*" | tee -a "$LOG"; }

say "# device: $(adb shell getprop ro.product.model | tr -d '\r') / Fire OS build $(adb shell getprop ro.build.version.name | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
say "# screen_off_timeout (ms): $(adb shell settings get system screen_off_timeout | tr -d '\r')"
say "# screensaver_enabled: $(adb shell settings get secure screensaver_enabled | tr -d '\r')  screensaver_components: $(adb shell settings get secure screensaver_components | tr -d '\r')"
adb logcat -c
say "# time  wakefulness  focus  keep_on  wm_lock"

while [ "$(date +%s)" -lt "$END" ]; do
  power="$(adb shell dumpsys power | tr -d '\r')"
  wake="$(echo "$power" | grep -m1 -o 'mWakefulness=[A-Za-z]*' | cut -d= -f2)"
  # The window manager holds a screen wake lock tagged WindowManager while a shown window has KEEP_SCREEN_ON.
  wmlock="no"; echo "$power" | grep -q "WAKE_LOCK *'WindowManager'" && wmlock="yes"
  focus="$(adb shell dumpsys window windows | tr -d '\r' | grep -m1 'mCurrentFocus')"
  infront="no"; echo "$focus" | grep -q "$PKG/" && infront="yes"
  # Jarvis's window attributes: newer Android prints flag names, older prints fl=#hex (KEEP_SCREEN_ON = 0x80).
  attrs="$(adb shell dumpsys window windows | tr -d '\r' | grep -A40 "$PKG/$PKG.MainActivity" | grep -m1 ' fl=')"
  keep="no"
  if echo "$attrs" | grep -q 'KEEP_SCREEN_ON'; then keep="yes"
  else
    hex="$(echo "$attrs" | grep -o 'fl=#[0-9a-fA-F]*' | head -1 | cut -c5-)"
    [ -n "$hex" ] && [ $(( 0x$hex & 0x80 )) -ne 0 ] && keep="yes"
  fi
  samples=$((samples + 1))
  verdict="PASS"
  if [ "$wake" != "Awake" ] || [ "$infront" != "yes" ] || [ "$keep" != "yes" ]; then verdict="FAIL"; fails=$((fails + 1)); fi
  say "$(date +%H:%M:%S)  $wake  $infront  $keep  $wmlock  $verdict"
  sleep "$EVERY"
done

lapses="$(adb logcat -d -s JarvisAwake:I | tr -d '\r' | grep -c -E 'lapsed|left the front')"
say "# JarvisAwake log:"
adb logcat -d -s JarvisAwake:I | tr -d '\r' | tee -a "$LOG"
say "# samples=$samples failed=$fails app_lapses=$lapses"
[ "$fails" -eq 0 ] && [ "$lapses" -eq 0 ]
