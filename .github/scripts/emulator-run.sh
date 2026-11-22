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
# Every adb call gets a time limit and no stdin (a hung call once stalled the whole run, and adb
# would otherwise swallow the scenario list being read).
a() { timeout 40 adb "$@" < /dev/null; }

alive() {
  if ! timeout 20 adb get-state < /dev/null >/dev/null 2>&1; then
    echo "!! The emulator isn't answering; reconnecting"
    adb kill-server >/dev/null 2>&1; adb start-server >/dev/null 2>&1; sleep 5
    timeout 60 adb wait-for-device < /dev/null || echo "!! Still gone"
    adb devices -l < /dev/null
  fi
}

quiet_dialogs() {
  a shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  # "isn't responding" boxes belong to the system: dumpsys names them without waiting for the UI to idle.
  if a shell dumpsys window windows 2>/dev/null | grep -q "Application Not Responding"; then
    a shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  fi
}

write_file() { # path, contents
  local tmp; tmp=$(mktemp); printf '%s' "$2" > "$tmp"
  a shell "mkdir -p $(dirname "$1")"
  a push "$tmp" "$1" >/dev/null 2>&1
  rm -f "$tmp"
  a shell "chown -R $uid:$uid /data/data/$PKG/files && restorecon -R /data/data/$PKG/files" >/dev/null 2>&1
}

scenario() { # name, plugins...
  local name="$1"; shift
  echo "== Scenario: $name ($*)"
  alive
  a shell am force-stop $PKG
  a shell "rm -f $DATA/deltacord-startup.json"
  if [ -n "${BUNDLE_URL:-}" ]; then
    write_file "$DATA/loader.json" "{\"customLoadUrl\":{\"enabled\":true,\"url\":\"$BUNDLE_URL\"}}"
  fi
  local plugins=() flags=""
  for word in "$@"; do
    case "$word" in
      +*) flags+="${word#+} " ;;
      -*) a shell "rm -f $DATA/${word#-}"; echo "   deleted ${word#-}" ;;
      *) plugins+=("$word") ;;
    esac
  done
  # Experiments for a debug build of Deltacord (words starting with +).
  if [ -n "$flags" ]; then write_file "$DATA/debug-flags.txt" "$flags"; echo "   flags: $flags"; else a shell "rm -f $DATA/debug-flags.txt"; fi
  set -- "${plugins[@]+"${plugins[@]}"}"
  if [ "$#" -gt 0 ]; then
    local json="{" first=1
    for p in "$@"; do
      [ $first = 1 ] || json+=","
      json+="\"$p\":{\"enabled\":true}"; first=0
    done
    json+="}"
    write_file "$DATA/plugins/settings.json" "$json"
  else
    a shell "rm -f $DATA/plugins/settings.json"
  fi
  a logcat -c
  a shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  for t in 20 45 "$WAIT"; do
    sleep $(( t == 20 ? 20 : (t == 45 ? 25 : WAIT - 45) ))
    quiet_dialogs
    timeout 40 adb exec-out screencap -p > "results/$name-$(printf '%03d' "$t")s.png" 2>/dev/null || true
  done
  # The screen's text; uiautomator waits for the UI to go idle, so it gets a short limit and a retry.
  for try in 1 2 3; do
    timeout 25 adb shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" < /dev/null > "results/$name-ui.xml" 2>/dev/null
    grep -q "<hierarchy" "results/$name-ui.xml" && break
    sleep 3
  done
  grep -o 'text="[^"]*"' "results/$name-ui.xml" | sed 's/text="//; s/"$//' | grep -v '^$' | head -40 > "results/$name-text.txt" || true
  a logcat -d > "results/$name-logcat.txt" 2>/dev/null || true
  a shell "cat $DATA/deltacord-startup.json" > "results/$name-startup.json" 2>/dev/null || true
  a shell "cat $DATA/plugins/settings.json" > "results/$name-plugins.json" 2>/dev/null || true
  a shell "ls -laR /data/data/$PKG/files/pyoncord /data/data/$PKG/cache/deltacord" > "results/$name-files.txt" 2>&1 || true
  a shell "cat $DATA/current-theme.json" 2>/dev/null | head -c 400 > "results/$name-theme-head.txt" || true
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
