#!/usr/bin/env bash
# N rounds of panel in / out: More menu open + close, picker in + out (lesson card back to the demo). Shots after.
set -u
N="${1:-30}"; J="$(dirname "$0")/jc.sh"; DEV="${DEV:-emulator-5560}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
for i in $(seq 1 "$N"); do
  "$J" cmd menu; "$ADB" -s $DEV shell sleep 0.6; "$J" cmd menu; "$ADB" -s $DEV shell sleep 0.6
  "$J" cmd next; "$ADB" -s $DEV shell sleep 0.6; "$J" cmd 'ui card_1'; "$ADB" -s $DEV shell sleep 0.8
done
echo "rounds=$N menu_open=$("$J" log 'EVENT MoreClicked' | wc -l) picker=$("$J" log 'JC-STATE S02' | wc -l)"
