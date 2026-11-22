#!/usr/bin/env bash
# Installs the patched Discord on the running emulator, opens it (logged out, a fresh install)
# and collects what happened into results/.
set -u
mkdir -p results
PKG=com.discord
WAIT="${WAIT:-100}"

adb root >/dev/null 2>&1 || true
sleep 3
adb wait-for-device

echo "== Installing"
adb install-multiple -r -g patched/*.apk 2>&1 | tee results/install.txt
adb shell pm list packages | grep -c "$PKG" || { echo "::error::Discord didn't install"; exit 1; }

# Point the module at the bundle being tested (it downloads the released one otherwise).
if [ -n "${BUNDLE_URL:-}" ]; then
  uid=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
  adb shell "mkdir -p /data/data/$PKG/files/pyoncord && echo '{\"customLoadUrl\":{\"enabled\":true,\"url\":\"$BUNDLE_URL\"}}' > /data/data/$PKG/files/pyoncord/loader.json && chown -R $uid:$uid /data/data/$PKG/files && restorecon -R /data/data/$PKG/files"
  echo "== Bundle: $BUNDLE_URL"
  adb shell "cat /data/data/$PKG/files/pyoncord/loader.json"
fi

adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "== Launched"

# A screenshot every 10 seconds, so the animation, the logo and whatever follows are all seen.
for t in $(seq 10 10 "$WAIT"); do
  sleep 10
  adb exec-out screencap -p > "results/screen-$(printf '%03d' "$t")s.png" 2>/dev/null || true
done

echo "== Collecting"
adb logcat -d > results/logcat.txt 2>/dev/null || true
adb shell dumpsys activity activities 2>/dev/null | grep -E "mResumedActivity|topResumedActivity|ResumedActivity" > results/activity.txt || true
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml results/ui.xml >/dev/null 2>&1 || true
mkdir -p results/pyoncord
for f in deltacord-startup.json startup-recovery.json font-families.txt current-theme.json deltacord-doctor-report.txt freeze-report.txt; do
  adb shell "cat /data/data/$PKG/files/pyoncord/$f" > "results/pyoncord/$f" 2>/dev/null || rm -f "results/pyoncord/$f"
done
adb shell "ls -la /data/data/$PKG/files/pyoncord /data/data/$PKG/cache/deltacord" > results/files.txt 2>&1 || true

# The words on screen (from the layout dump), for a quick read of what's showing.
if [ -f results/ui.xml ]; then
  grep -o 'text="[^"]*"' results/ui.xml | sed 's/text="//; s/"$//' | grep -v '^$' | head -60 > results/on-screen-text.txt || true
  echo "== On screen:"; cat results/on-screen-text.txt
fi
echo "== Startup record:"; cat results/pyoncord/deltacord-startup.json 2>/dev/null | head -c 3000; echo
echo "== Deltacord in the log:"; grep -iE "deltacord|lspatch|AndroidRuntime|FATAL" results/logcat.txt | tail -40
exit 0
