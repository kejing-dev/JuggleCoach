#!/usr/bin/env bash
# Side-panel states with the camera turned ~20 degrees left (the panel stands 42 degrees left; design QA note).
set -u
J="$(dirname "$0")/jc.sh"; H="$(dirname "$0")/harness/emu.sh"
export DEV="${DEV:-emulator-5560}" AVD="${AVD:-Pico_Emulator_6_1_fresh}"
DIR="${1:-C:/Users/Admin/Downloads/spatial-app-factory/apps/juggle-coach/build/shots}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
shot() { local name="$1"; shift; for c in "$@"; do "$J" cmd "$c"; done
  "$H" fast on >/dev/null 2>&1; "$ADB" -s "$DEV" shell sleep 5; "$H" fast on >/dev/null 2>&1; "$ADB" -s "$DEV" shell sleep 4
  "$J" shot "$name" "$DIR" >/dev/null; echo "$name: $("$J" state)"; }
"$J" cmd release; "$J" cmd 'spike=0'
for lang in zh en; do
  "$J" cmd "lang=$lang"
  shot "left20-$lang-S04r" 'course=4' 'state=S04r'
  shot "left20-$lang-S05a" 'course=4' 'state=S05a'
  shot "left20-$lang-S06" 'course=4' 'state=S06'
  shot "left20-$lang-S07" 'course=4' 'state=S07'
  shot "left20-$lang-S10" 'course=4' 'state=S10'
  shot "left20-$lang-S09" 'course=4' 'state=S09'
  shot "left20-$lang-K2" 'state=K2'
done
"$J" cmd 'lang=zh'; "$J" cmd release
