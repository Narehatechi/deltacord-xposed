#!/usr/bin/env bash
# Installs the patched Discord on the running emulator and opens it logged out, once per scenario
# in .github/emulator-scenarios.txt (each a name and the plugins to switch on), collecting what
# happened into results/.
set -u
mkdir -p results
PKG=com.discord
DATA=/data/data/$PKG/files/pyoncord
WAIT="${WAIT:-70}"

adb root >/dev/null 2>&1 || true
sleep 3
adb wait-for-device

echo "== Installing"
adb install-multiple -r -g patched/*.apk 2>&1 | tee results/install.txt
adb shell pm list packages | grep -c "$PKG" || { echo "::error::Discord didn't install"; exit 1; }
uid=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')

# The emulator's launcher is slow and its "isn't responding" box covers the screen.
quiet_dialogs() {
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  adb shell "uiautomator dump /sdcard/d.xml >/dev/null 2>&1; cat /sdcard/d.xml" 2>/dev/null | grep -q "isn't responding" && adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
}

write_file() { # path, contents
  adb shell "mkdir -p $(dirname "$1") && cat > $1" <<< "$2"
  adb shell "chown -R $uid:$uid /data/data/$PKG/files && restorecon -R /data/data/$PKG/files" >/dev/null 2>&1
}

scenario() { # name, plugins...
  local name="$1"; shift
  echo "== Scenario: $name ($*)"
  adb shell am force-stop $PKG
  adb shell "rm -f $DATA/deltacord-startup.json"
  if [ -n "${BUNDLE_URL:-}" ]; then
    write_file "$DATA/loader.json" "{\"customLoadUrl\":{\"enabled\":true,\"url\":\"$BUNDLE_URL\"}}"
  fi
  if [ "$#" -gt 0 ]; then
    local json="{" first=1
    for p in "$@"; do
      [ $first = 1 ] || json+=","
      json+="\"$p\":{\"enabled\":true}"; first=0
    done
    json+="}"
    write_file "$DATA/plugins/settings.json" "$json"
  else
    adb shell "rm -f $DATA/plugins/settings.json"
  fi
  adb logcat -c
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  for t in 20 45 "$WAIT"; do
    sleep $(( t == 20 ? 20 : (t == 45 ? 25 : WAIT - 45) ))
    quiet_dialogs
    adb exec-out screencap -p > "results/$name-$(printf '%03d' "$t")s.png" 2>/dev/null || true
  done
  adb shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" > "results/$name-ui.xml" 2>/dev/null || true
  grep -o 'text="[^"]*"' "results/$name-ui.xml" | sed 's/text="//; s/"$//' | grep -v '^$' | head -40 > "results/$name-text.txt" || true
  adb logcat -d > "results/$name-logcat.txt" 2>/dev/null || true
  adb shell "cat $DATA/deltacord-startup.json" > "results/$name-startup.json" 2>/dev/null || true
  adb shell "cat $DATA/plugins/settings.json" > "results/$name-plugins.json" 2>/dev/null || true
  if grep -q "Log In" "results/$name-text.txt"; then verdict="LOGIN SCREEN"; else verdict="STUCK? ($(head -3 "results/$name-text.txt" | tr '\n' '|'))"; fi
  echo "RESULT $name: $verdict" | tee -a results/SUMMARY.txt
  grep -iE "ReactNativeJS.*(error|TypeError|undefined is not|cannot read)" "results/$name-logcat.txt" | head -8
}

# First launch: Discord sets itself up (and Deltacord downloads); not judged.
scenario warmup

while read -r name plugins; do
  [ -z "$name" ] && continue
  case "$name" in \#*) continue ;; esac
  # shellcheck disable=SC2086
  scenario "$name" $plugins
done < .github/emulator-scenarios.txt

echo "== Summary"; cat results/SUMMARY.txt
exit 0
