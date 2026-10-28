#!/usr/bin/env bash
# Every designed state (design stateModel.qaCombos) from the default view, held by debug commands.
# Run from tools/: ./shots_all.sh [DIR]. Camera: straight ahead at the reset pose; body frame pinned at the head.
set -u
J="$(dirname "$0")/jc.sh"
H="$(dirname "$0")/harness/emu.sh"
export DEV="${DEV:-emulator-5560}" AVD="${AVD:-Pico_Emulator_6_1_fresh}"
DIR="${1:-C:/Users/Admin/Downloads/spatial-app-factory/apps/juggle-coach/build/shots}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
wait_s() { "$ADB" -s "$DEV" shell sleep "$1"; }
shot() { # NAME CMD...
  local name="$1"; shift
  for c in "$@"; do "$J" cmd "$c"; done
  "$H" fast on >/dev/null 2>&1; wait_s 5; "$H" fast on >/dev/null 2>&1; wait_s 2
  "$J" shot "$name" "$DIR" >/dev/null; echo "$name: $("$J" state)"
}
"$J" cmd release; "$J" cmd 'lang=zh'; "$J" cmd 'anchor=0,0,0'
shot S00 'state=S00'
shot S01 'state=S01'
shot S01+S08 'state=S01' 'dwell=0.9'
"$J" cmd 'dwell=off'
shot S02 'state=S02'
shot S02+S08 'state=S02' 'dwell=0.9'
"$J" cmd 'dwell=off'
shot S03 'state=S03'
shot S04-1-legend 'release' 'legend=reset' 'course=1' 'state=S04'
shot S04-4-three 'course=4' 'legend=reset' 'state=S04'
shot S04-4-phase05 'course=4' 'state=S04' 'phase=0.5'
shot S04-ready 'course=1' 'state=S04r'
shot S05a 'course=1' 'state=S05a'
shot S05-1-phase09 'course=1' 'state=S05' 'phase=0.9'
shot S05-4-phase033 'course=4' 'state=S05' 'phase=0.33'
shot S05-3 'course=3' 'state=S05' 'phase=0.5'
shot S05-5 'course=5' 'state=S05' 'phase=0.5'
shot S05-6 'course=6' 'state=S05' 'phase=0.5'
shot S05+S08 'course=1' 'state=S05' 'phase=0.2' 'dwell=0.9'
"$J" cmd 'dwell=off'
shot S06 'course=1' 'state=S06'
shot S07 'course=1' 'state=S07'
shot S09 'course=1' 'state=S09'
shot S10 'course=1' 'state=S10'
shot S11 'state=S11'
shot S13 'release' 'gaze=head' 'state=S04r'
shot K1 'course=1' 'state=K1'
shot K2 'state=K2'
"$J" cmd 'lang=en'
shot S12-picker 'state=S02'
shot S12-S04 'course=4' 'state=S04r'
shot S12-S09 'state=S09'
"$J" cmd 'lang=zh'; "$J" cmd release; "$J" cmd 'spike=0'; "$J" cmd 'state=S04r'
