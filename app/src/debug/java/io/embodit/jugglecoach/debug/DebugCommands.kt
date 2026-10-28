package io.embodit.jugglecoach.debug

import android.content.Context
import io.embodit.jugglecoach.domain.juggle.BodyFrame
import io.embodit.jugglecoach.domain.juggle.Course
import io.embodit.jugglecoach.domain.model.LanguagePref
import io.embodit.jugglecoach.domain.model.DemoSlow
import io.embodit.jugglecoach.domain.model.SetSize
import io.embodit.jugglecoach.domain.model.Vec3
import io.embodit.jugglecoach.platform.JcLog
import io.embodit.jugglecoach.ui.jc.JcEvent
import io.embodit.jugglecoach.ui.jc.debug.JcProbe
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `qaHooks`). One log line (tag `JuggleCoach`) answers every command.
 * Accepted forms: `--es cmd 'state=S04'`, `--es cmd 'state S04'`, `--es cmd state --es v S04`.
 *
 * - `state=S00|S01|S02|S03|S04|S04r|S05a|S05|S06|S07|S09|S10|S11|K1|K2`  straight to a state, held against real sensing
 *                            until `release` (S04 = demo playing, S04r = ready pose; S05a = count-in held at "3")
 * - `course=1..6`, `vt=0.5..1.3`, `demo=0.3|0.5|1`, `setcount=6|10|20|inf`, `lang=zh|en`, `mute=1|0`
 * - `phase=<0..1>|off`       freeze the lesson clock at that share of the lesson's cycle (P1: 0.5 = at the peak)
 * - `freeze=1|0`             freeze the clock where it is / let it run
 * - `dwell=<0..1.2>|off`     a held dwell time (s) on the left disc (S01: the welcome button, S02: the chosen card)
 * - `gaze=eye|head|invalid`  head: S13 (no eyes); eye: eyes back; invalid: forces the transient fallback (amber dot)
 * - `drift=1|0`              S10 on / off (held)
 * - `anchor=<x>,<z>,<yawDeg>` body frame straight to Stage coordinates (eye height = the head's)
 * - `shoulder=<m>`, `handdrop=<m>`, `peak=<m>`, `zfront=<m>`  body parameters (clamped to the menu ranges)
 * - `blocked=1|0`, `spike=1|0`, `pitch=<deg>|off`, `legend=reset`, `testtex=1|0` ("F↑" on every plate), `clear`
 * - `release`                end every debug hold
 * - `main`, `prev`, `next`, `watch`, `menu`, `realign`, `exit`, `ui <control>` (panel controls by name)
 * - `dump`, `hmd`, `panels`, `geom`, `clock`
 */
internal object DebugCommands {

    fun run(context: Context, extras: Map<String, String>) {
        val raw = extras["cmd"]?.trim() ?: return
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return
        var name = tokens.removeAt(0).lowercase(Locale.US)
        val args = ArrayList<String>()
        if (name.contains('=')) {
            val v = name.substringAfter('=')
            name = name.substringBefore('=')
            args += v.split(',').filter { it.isNotEmpty() }
        }
        for (t in tokens) args += t.split(',').filter { it.isNotEmpty() }
        extras["v"]?.let { args += it.split(',') }
        val host = JcProbe.host
        if (host == null) {
            JcLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            dispatch(context, host, name, args)
        } catch (t: Throwable) {
            JcLog.e("CMD $name failed: $t", t)
        }
    }

    private fun dispatch(context: Context, host: JcProbe.Host, name: String, args: List<String>) {
        val scene = host.scene()
        val first = args.getOrNull(0)?.lowercase(Locale.US) ?: ""
        fun num(i: Int): Float? = args.getOrNull(i)?.toFloatOrNull()
        fun off(): Boolean = first == "off" || first == "none" || first == "null"
        when (name) {
            "state", "goto" -> host.dispatch(JcEvent.DebugState(args.getOrNull(0) ?: return JcLog.i("CMD state needs S00..S13 / K1 / K2")))
            "course" -> host.dispatch(JcEvent.DebugCourse(Course.of(num(0)?.toInt() ?: -1) ?: return JcLog.i("CMD course needs 1..6")))
            "vt" -> host.dispatch(JcEvent.DebugVt(num(0) ?: return JcLog.i("CMD vt needs m/s")))
            "demo" -> host.dispatch(JcEvent.DemoSlowSelected(when (first) { "0.3" -> DemoSlow.X03; "1" -> DemoSlow.X1; else -> DemoSlow.X05 }))
            "setcount" -> host.dispatch(JcEvent.SetSizeSelected(when (first) { "6" -> SetSize.SIX; "20" -> SetSize.TWENTY; "inf" -> SetSize.INF; else -> SetSize.TEN }))
            "lang" -> host.dispatch(JcEvent.LanguageSelected(when (first) { "zh" -> LanguagePref.ZH; "en" -> LanguagePref.EN; else -> LanguagePref.SYSTEM }))
            "mute" -> host.dispatch(JcEvent.MuteSet(first != "0" && first != "off"))
            "phase" -> host.dispatch(JcEvent.DebugPhase(if (off()) null else num(0)?.coerceIn(0f, 1f)))
            "freeze" -> {
                val t = scene?.clockLine()
                if (first == "0" || first == "off") host.dispatch(JcEvent.DebugPhase(null)) else JcLog.i("CMD freeze: use phase=<0..1> ($t)")
            }
            "dwell" -> host.dispatch(JcEvent.DebugDwell(if (off()) null else num(0)?.coerceIn(0f, 1.5f)))
            "gaze" -> when (first) {
                "head" -> host.dispatch(JcEvent.DebugHeadFallback(true))
                "eye" -> host.dispatch(JcEvent.DebugHeadFallback(false))
                "invalid" -> host.dispatch(JcEvent.EyeState(permanentUnavailable = false, amber = true))
                else -> JcLog.i("CMD gaze needs eye|head|invalid")
            }
            "drift" -> host.dispatch(JcEvent.DebugDrift(first != "0" && first != "off"))
            "anchor" -> {
                if (off()) return host.dispatch(JcEvent.DebugAnchor(null))
                val x = num(0) ?: return JcLog.i("CMD anchor needs x,z,yawDeg")
                val z = num(1) ?: return JcLog.i("CMD anchor needs x,z,yawDeg")
                val yaw = Math.toRadians((num(2) ?: 0f).toDouble()).toFloat()
                val y = host.uiState().frame?.origin?.y ?: 1.65f
                host.dispatch(JcEvent.DebugAnchor(BodyFrame(Vec3(x, y, z), yaw)))
            }
            "shoulder", "handdrop", "peak", "zfront" -> {
                val v = num(0) ?: return JcLog.i("CMD $name needs metres")
                val b = host.uiState().body
                host.dispatch(
                    JcEvent.DebugBody(
                        when (name) {
                            "shoulder" -> b.copy(shoulder = v)
                            "handdrop" -> b.copy(handDrop = v)
                            "peak" -> b.copy(peakRise = v)
                            else -> b.copy(zFront = v)
                        }
                    )
                )
            }
            "blocked" -> host.dispatch(JcEvent.DebugBlocked(first != "0" && first != "off"))
            "spike" -> host.dispatch(JcEvent.DebugSpike(first != "0" && first != "off"))
            "pitch" -> host.dispatch(JcEvent.DebugPitch(if (off()) null else num(0)))
            "legend" -> host.dispatch(JcEvent.DebugLegendReset)
            "testtex" -> host.dispatch(JcEvent.DebugTestTexture(first != "0" && first != "off"))
            "clear" -> host.dispatch(JcEvent.DebugClear)
            "release" -> host.dispatch(JcEvent.DebugRelease)
            "main", "start", "pause" -> host.dispatch(JcEvent.MainClicked)
            "next" -> host.dispatch(JcEvent.NextClicked)
            "prev" -> host.dispatch(JcEvent.PrevClicked)
            "watch" -> host.dispatch(JcEvent.WatchClicked)
            "menu", "more" -> host.dispatch(JcEvent.MoreClicked)
            "realign" -> host.dispatch(JcEvent.RealignClicked)
            "exit" -> host.dispatch(JcEvent.ExitClicked)
            "ui" -> {
                val control = args.getOrNull(0) ?: return JcLog.i("CMD ui needs a control name")
                JcLog.i(if (host.pressControl(control)) "CMD ui $control pressed" else "CMD ui $control not composed (open its panel first)")
            }
            "dump" -> {
                val json = host.dumpJson()
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
                JcLog.i("DUMP $json")
            }
            "hmd" -> JcLog.i(scene?.poseLine() ?: "POSE no scene")
            "panels", "bounds" -> scene?.panelLines()?.forEach { JcLog.i(it) } ?: JcLog.i("PANEL no scene")
            "geom" -> JcLog.i(scene?.geomLine() ?: "GEOM no scene")
            "clock" -> JcLog.i(scene?.clockLine() ?: "CLOCK no scene")
            else -> JcLog.i("CMD $name unknown")
        }
    }
}
