# Wall soak test

This test proves that Jarvis Wall keeps the Fire TV screen on for hours with no remote input. Run it on the wall Fire Stick, or on any Fire TV, after each change to the keep-screen-on code (`data/KeepAwake.kt`, `MainActivity`).

## How the screen stays on

- `App()` holds `ScreenAwake.WALL` while `Screen.Wall` is anywhere in the back stack.
- `KeepAwakeGuard` sets `FLAG_KEEP_SCREEN_ON` on the `MainActivity` window while any hold is on. It sets the flag again on each `onResume`.
- Once a minute, while Jarvis is in front, the guard checks the window. If the flag is off while a hold is on, the guard sets it again and logs a lapse.
- The guard also logs when Jarvis leaves the front while the Wall is up, for example when another app, a system screen or the screensaver covers it.
- Logs go to logcat with the tag `JarvisAwake`. Lapses also go to Settings > About and help on the TV.
- Android stops the screen timeout while a shown window has `FLAG_KEEP_SCREEN_ON`. The window manager does this through a screen wake lock tagged `WindowManager`. If the app goes to the background, the flag stops working, and the screen can time out as usual. That is correct.

## What you need

- The Fire TV and a computer on the same network. Turn on ADB debugging on the TV (Settings > My Fire TV > Developer options).
- `adb` on the computer. Run `adb connect <tv-ip>`.
- The Jarvis build from this branch on the TV.

## Automated check

The QA workflow (`.github/qa/run-qa.sh`, step 4w) runs a short version of this test in the Android TV emulator with a 15 second idle timeout. It runs the control, the Wall, the density change and the rebuild. Use this soak test on the real Fire TV for the long runs.

## Set up

1. On the TV, open Settings > Display & Sounds > Screen Saver. Set Start Time to its shortest value. Write down that value as T. Amazon's documentation was not available when this protocol was written, so use what the TV shows and do not assume a value.
2. Make sure the Wall address and key are set, so the Wall shows photos and the calendar.
3. Do not touch the remote during a run. A key press resets the screensaver timer, and the run then proves nothing.

## Control run (proves that the probe sees a failure)

1. Open Jarvis Home. Do not open the Wall.
2. Run `tools/soak/wall-soak.sh <2×T in minutes> 30`.
3. Expected result: after about T, samples show `mWakefulness=Dreaming` (the screensaver) and `FAIL`. If no sample fails, the probe or the screensaver setting is wrong. Fix that before you continue.

## Soak run

1. Open the Wall: Settings > Jarvis Wall > Open Wall, or Start Wall on the Control page. Use the Control page at least once, because before this fix that path did not survive a rebuild.
2. Run `tools/soak/wall-soak.sh <minutes> 60`. Use at least 6×T. For a full pass, also do one overnight run of 8 hours or more.
3. Display test, during the run: run `adb shell wm density 240`, then `adb shell wm density reset`. The app handles density changes itself (as it does colorMode and fontScale, which an HDMI mode change can also cause). The Wall must stay up with no rebuild.
4. Rebuild test, during the run: run `adb shell settings put global always_finish_activities 1` ("Don't keep activities"). Press HOME, then open Jarvis again from the Fire TV home screen. Run `adb shell settings put global always_finish_activities 0`. The Wall must come back by itself, and logcat must show `Wall reopened after the app was rebuilt`. The HOME press logs `left the front`; that one line is expected and does not fail the run.

## Pass rule

The soak run passes when all of these are true:

- Every sample shows `mWakefulness=Awake`, Jarvis in front, and `KEEP_SCREEN_ON` on the Jarvis window (the script prints `PASS`).
- The run lasts at least 6×T with no remote input, and the overnight run lasts at least 8 hours.
- The `JarvisAwake` log has no `lapsed` lines, and no `left the front` lines except the one from the rebuild test.
- After the rebuild test, the Wall is back and the samples still pass.

The `wm_lock` column (the `WindowManager` screen wake lock in `dumpsys power`) is for information only. Its name can differ between Fire OS versions.

## Record the result

Paste the summary lines from the script into the pull request: the device line, `screen_off_timeout`, T, the run length and `samples=… failed=… app_lapses=…`.
