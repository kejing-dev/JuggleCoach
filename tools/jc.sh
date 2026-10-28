#!/usr/bin/env bash
# QA helper for Juggle Coach on the factory emulator (default emulator-5560).
#   tools/jc.sh cmd 'state=S04'        debug broadcast (see AGENTS.md)
#   tools/jc.sh shot NAME [DIR]        console screenshot (full Stage frame) -> DIR/NAME.png (default: factory build/shots)
#   tools/jc.sh log [REGEX]            JuggleCoach log lines (all, or matching REGEX)
#   tools/jc.sh state                  last JC-STATE line
#   tools/jc.sh launch | stop | install
set -u
DEV="${DEV:-emulator-5560}"
PKG=io.embodit.jugglecoach
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SHOTS="${SHOTS:-C:/Users/Admin/Downloads/spatial-app-factory/apps/juggle-coach/build/shots}"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$2'" >/dev/null ;;
  shot)
    dir="${3:-$SHOTS}"; tmp="$dir/.tmp"; mkdir -p "$tmp"; rm -f "$tmp"/*
    MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" emu screenrecord screenshot "$(cygpath -m "$tmp")" >/dev/null
    f=$(ls "$tmp" | head -1); [ -n "$f" ] && mv "$tmp/$f" "$dir/$2.png" && echo "$dir/$2.png" ;;
  log) if [ -n "${2:-}" ]; then "$ADB" -s "$DEV" logcat -d -s JuggleCoach:V | grep -E "$2"; else "$ADB" -s "$DEV" logcat -d -s JuggleCoach:V; fi ;;
  state) "$ADB" -s "$DEV" logcat -d -s JuggleCoach:V | grep "JC-STATE" | tail -1 | cut -c32- ;;
  launch) "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 ;;
  stop) "$ADB" -s "$DEV" shell am force-stop $PKG ;;
  install) "$ADB" -s "$DEV" install -r "$HERE/../app/build/outputs/apk/debug/app-debug.apk" ;;
  *) sed -n 2,8p "$0" ;;
esac
