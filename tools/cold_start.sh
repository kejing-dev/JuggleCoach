#!/usr/bin/env bash
# N clean starts, each left idle for 60 s (no fast-fps posting): counts "isn't responding" windows and am_anr events.
set -u
N="${1:-3}"; DEV="${DEV:-emulator-5560}"; PKG=io.embodit.jugglecoach
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
J="$(dirname "$0")/jc.sh"
for i in $(seq 1 "$N"); do
  "$ADB" -s $DEV shell am force-stop $PKG; "$ADB" -s $DEV logcat -c; "$ADB" -s $DEV logcat -b events -c
  "$J" launch
  for s in $(seq 1 12); do "$ADB" -s $DEV shell sleep 5; done
  anr=$("$ADB" -s $DEV logcat -b events -d | grep -c "am_anr.*$PKG")
  win=$("$ADB" -s $DEV shell dumpsys window windows | grep -c "Application Not Responding")
  echo "start $i: am_anr=$anr anr_windows=$win $("$ADB" -s $DEV logcat -d -s JuggleCoach:V | grep -E 'SCENE attached|JC-STATE' | head -3 | cut -c19-120 | tr '\n' ' ')"
done
