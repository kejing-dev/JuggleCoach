# Juggle Coach (抛接)

A life-size scarf-juggling path drawn on your own body: two crossing throw paths 0.40 m in front of your chest, scaled
to your shoulder width and eye height, ghost scarves flying them at real scarf speed, a beat to throw on and hand discs
that light up when to catch. Hands stay on the real scarves: start / pause / next lesson by looking at a 3D disc for
1.2 s. PICO OS 6, Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space, passthrough), package
`io.embodit.jugglecoach`, v0.1.0 (= design milestone M1: six lessons; the M0 device spike checklist is kept in the menu).

Design package (binding): `../spatial-app-factory/apps/juggle-coach/design-package.json` (revision 3, archetype D,
container STAGE_MIXED, window model single_panel_with_popup, states S00 - S13 + K1 / K2, 23 interactions, 6 patterns).
Skeleton: `../figure-model` (Stage, HeadTracker, PanelRig pool, fonts, debug receiver); eye tracking from
RehearsalHall v0.1.2 (`EyeTracker`, `EyePermissionActivity`); the AudioTrack clock pattern from `../dance-steps`.

Built by the factory's build station on 2026-10-28; self-tested on emulator-5560 (AVD Pico_Emulator_6_1_fresh). Not run
on a headset yet.

## Structure

```
app/src/main/java/io/embodit/jugglecoach/
  Main.kt                      DefaultStage { JuggleTheme { JcScreen() } }
  platform/                    SpatialApplication, LaunchActivity, JcGraph (wiring), JcLog (tag JuggleCoach),
                               EyePermissionActivity (com.picovr.permission.EYE_TRACKING)
    tracking/HeadTracker       HMD pose, block reason                     [Spatial Ruler / Figure Model]
    tracking/EyeTracker        eye gaze pose (EyeTrackingProvider)        [RehearsalHall]
    audio/BeatEngine           one long-lived 48 kHz stereo AudioTrack; the lesson clock (getTimestamp)
    audio/BeatSounds           "da" 660 Hz (right), "dong" 440 Hz (left), "pu" catch noise, rendered once
  domain/model                 Vec3 / Quat4, JuggleModel (settings, body parameters, enums)
  domain/juggle/Lesson         the six patterns: paths (carry / rise / terminal-speed fall), the endless throw timeline,
                               scarves / cues / counts / sounds at any lesson time; BodyFrame (head-anchored frame)
  data/SettingsStore           SharedPreferences (settings, last set per lesson, first-run flags, spike record)
  ui/theme                     JcColors (the only colour literals), JuggleTheme (PicoTheme), Noto fonts
  ui/jc                        JcViewModel (state machine), JcUiState / JcEvent, JcScreen
  ui/jc/components             JcStage (SpatialView + panel pool), ControlPanel, Pages (welcome, picker), MoreMenu (+ K2),
                               BlockedDialog, Common
  ui/jc/scene                  JcScene (the one ECS System: alignment, lesson clock, content, drift, gaze dwell, panel
                               follow), JcViews (paths, peak rings, hand discs, scarves, dwell discs, gaze dot, reticle,
                               count-in digit), Textures (hand / icon / digit / "F↑" bitmaps), PanelRig, PanelSpec, SceneKit
app/src/debug/                 DebugCommandReceiver + DebugCommands (QA hooks)
app/src/test/                  LessonTest: the design's timing / points, phases, counts; writes build/sounds/*.wav
tools/                         jc.sh (cmd / shot / log), shots_all.sh, shots_panel.sh, cold_start.sh, stress_panels.sh,
                               make_icon.py, harness/ (emulator input: fast fps, drags, keys, camera toggles)
```

One direction of data: panel controls, scene milestones (panels ready, aligned, count-in done, counted, set done, demo
done, drift, dwell, eye / block state, count-in beat) and debug hooks -> `JcViewModel.onEvent` -> `JcUiState` ->
`JcScene.render` and the panels. Everything that moves is written per frame in `JcScene.onFrame` from the audio clock and
never goes through Compose.

## The lesson clock

- Lesson time 0 = the first release; the count-in runs 4 x 0.8 s before it (beats R L R L, digits 4 3 2 1 over the hand
  discs). `BeatEngine.Run(lesson, startAt, speed, countIn, endAt)` is armed with its own `Lesson` copy (the mix thread);
  the scene reads `lessonTime()` each frame. Demo ("watch once") = 3 rounds at the demo slow-motion (0.3 / 0.5 / 1x),
  sounds at stretched times, no count-in. Follow-along is always 1:1. Muted = zeros written, the clock keeps running.
- Timing (defaults: shoulder 0.40, hand drop 0.35, ahead 0.40, peak +0.20, vt 0.9): carry 0.31 s, rise 0.29 s, fall
  0.61 s, F = 0.905 s; P1 period 1.82 s, P2 2.3, P3 1.97, P4 I = 0.44, P5 I = 0.70, P6 I = 0.45 (unit test).
- Debug `phase=` windows: P1 / P3 from carry start over carry + F (0.5 = at the peak, 0.9 = about to land), P2 over
  carry + 1.35 F, P4 / P6 from release 0 over 3 I (0.33 = one just past the top, one at release, one in hand), P5 over 2 I.

## Rules as built

- Body frame F = median head pose over 1.0 s (S03 reticle). Drift: > 0.30 m or > 25 degrees for 1.5 s -> S10 (content
  60 %, auto pause, discs follow the head); head still 1 s -> frame slides 0.5 s to the new pose. Looking at the panel or
  a dwell disc never counts as drifting (both stand 42 degrees off F).
- Panel: S01 / S02 / first half of S03 in front 0.7 m, 0.05 m below the eyes (dead zone +/-30 degrees); otherwise 42
  degrees left, 0.9 m, control panel centre 0.05 m above the eyes, other contents hang from its top edge; yaw only.
- Gaze: eye pose when fresh, else head direction (S13 permanent when refused / unsupported; amber dot when the eyes give
  no sample for 0.3 s). Dwell: 0.3 s before the rim starts, full at the dwell time (1.0 / 1.2 / 1.5 s), 0.8 s cool-down,
  a target already under the gaze when the state changes waits until the gaze leaves it. A system-pointer tap on a disc
  also counts.
- Textures sample v from the bottom (factory finding); verified with `testtex=1` ("F↑" upright on every plate).

## QA hooks (debug build only)

```
adb -s emulator-5560 shell "am broadcast -a io.embodit.jugglecoach.DEBUG -p io.embodit.jugglecoach --es cmd 'state=S05'"
tools/jc.sh cmd 'state=S05'      # same, through the helper
```

| command | effect |
|---|---|
| `state=S00..S07`, `S04r`, `S05a`, `S09`, `S10`, `S11`, `K1`, `K2` | straight to a state and hold it against real sensing until `release`. S04 = demo playing, S04r = ready pose, S05a = count-in held at "3", S05 / S06 = phase 0.5, S10 = drifted + paused, K1 = spike following, K2 = spike checklist |
| `course=1..6`, `vt=<m/s>`, `demo=0.3\|0.5\|1`, `setcount=6\|10\|20\|inf`, `lang=zh\|en`, `mute=1\|0` | lesson and settings |
| `phase=<0..1>\|off` | freeze the lesson clock at that share of the cycle (see above) |
| `dwell=<s>\|off` | held dwell time on the left disc (S01: welcome button, S02: highlighted card) |
| `gaze=eye\|head\|invalid`, `drift=1\|0`, `anchor=<x>,<z>,<yawDeg>\|off` | S13 / eyes back / amber dot; S10; body frame to Stage coordinates |
| `shoulder= handdrop= peak= zfront=` (m) | body parameters (clamped to the menu ranges) |
| `blocked=1\|0`, `spike=1\|0`, `testtex=1\|0`, `legend=reset`, `clear`, `release` | S11; spike build behaviour; "F↑" on every plate; first-demo legend again; wipe saved data; end every hold |
| `main`, `prev`, `next`, `watch`, `menu`, `realign`, `exit`, `ui <control>` | panel actions (`ui` names: main, prev, next, more, watch, sound, speed_floaty/normal/fast, card_1..6, welcome_go, shoulder_up/down, hand_drop_up/down, peak_up/down, z_front_up/down, vt_up/down, slow_03/05/1, set_6/10/20/inf, beat_on/off, dwell_10/12/15, gaze_ask, auto_on/off, lang_zh/en, menu_realign, menu_exit, menu_close, menu_spike, spike_0..2, spike_save, dialog_retry, dialog_exit) |
| `dump`, `hmd`, `panels`, `geom`, `clock` | queries |

Log tag `JuggleCoach`: `JC-STATE <qaName> course= vt= phase= n=` on every state change (qaName = base[+overlays][:course],
e.g. `S05+S08+S13:4`), `JC-GEOM` (path points, discs, peak in Stage) after alignment and each parameter change,
`JC-SCHED` (F, carry, I, period) when a run is armed, `JC-SPIKE` on "Save record", plus `ALIGN`, `DRIFT`, `DWELL`, `EYE`,
`AUDIO`, `PANEL`, `TAP`, `EVENT`, `CMD`.

Screenshot list (design `stateModel.qaCombos`): `tools/shots_all.sh` (default view, body frame pinned with `anchor=0,0,0`)
and `tools/shots_panel.sh` (camera turned ~20 degrees left first: the panel stands 42 degrees left; zh and en). The
emulator's view is narrow (about +/-48 degrees across): hand discs at -41 degrees need the camera pitched down ~35
degrees. Scripts default to emulator-5560.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon --offline
.\gradlew.bat testDebugUnitTest --no-daemon --offline
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` must be
empty. Icon: `python tools/make_icon.py ../spatial-app-factory/apps/juggle-coach/icon.png`.

## Deviations (recorded in the factory's state.json)

v0.1.0 is M1 directly (device spike checklist kept in the menu); four pooled panels instead of one panel changing
content; panels 440 dp wide, picker 480 x 560 with 220 x 116 cards, speed segments 264 dp; stepper rows use icon keys
(the SpatialUI Stepper's - / + are too pale on glass); scarves are two-layer feather meshes; sounds rendered in memory;
gazing at a disc / the panel never counts as drift; speed changes during a set apply from the next count-in; paths are
flat ribbons facing the user.
