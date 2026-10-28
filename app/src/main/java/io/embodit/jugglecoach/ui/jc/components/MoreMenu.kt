package io.embodit.jugglecoach.ui.jc.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.jugglecoach.R
import io.embodit.jugglecoach.domain.model.BodyParams
import io.embodit.jugglecoach.domain.model.DemoSlow
import io.embodit.jugglecoach.domain.model.DwellTime
import io.embodit.jugglecoach.domain.model.LanguagePref
import io.embodit.jugglecoach.domain.model.SetSize
import io.embodit.jugglecoach.ui.jc.BodyField
import io.embodit.jugglecoach.ui.jc.JcEvent
import io.embodit.jugglecoach.ui.jc.JcUiState
import io.embodit.jugglecoach.ui.jc.MenuPage
import io.embodit.jugglecoach.ui.jc.debug.probe
import io.embodit.jugglecoach.ui.jc.scene.PanelSpec
import kotlin.math.roundToInt

/**
 * The More menu (design `panels.moreMenu`): the control panel's place, 440 x 696 dp, hanging from the same top edge.
 * Fourteen 48 dp rows: shoulder width, hand disc height, peak, path distance, scarf speed fine-tune (SpatialUI steppers),
 * demo slow-mo, throws per set, beat, dwell time, gaze source, auto demo, language (segments), re-align / quit / close,
 * and the spike record (K2: the device spike checklist, design `milestones.M0`). Value rows apply at once (the 3D
 * content re-lays out over 0.3 s) and the menu stays open.
 */
@Composable
internal fun MoreMenu(state: JcUiState, filled: Boolean, onEvent: (JcEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Glass(w.dp, h.dp, corner = 28.dp, padding = 12.dp, spacing = 0.dp) {
        if (!filled) return@Glass
        if (state.menuPage == MenuPage.SPIKE) {
            SpikePage(state, onEvent)
            return@Glass
        }
        val b = state.body
        val zh = rs(R.string.lang_code) == "zh"
        StepRow(R.drawable.ic_shoulder, rs(R.string.menu_shoulder), rs(R.string.val_cm, cm(b.shoulder)), "shoulder", b.shoulder > BodyParams.SHOULDER_MIN + 1e-3f, b.shoulder < BodyParams.SHOULDER_MAX - 1e-3f) { onEvent(JcEvent.BodyStep(BodyField.SHOULDER, it)) }
        StepRow(R.drawable.ic_hand, rs(R.string.menu_hand_drop), rs(R.string.val_below_eye, cm(b.handDrop)), "hand_drop", b.handDrop > BodyParams.HAND_DROP_MIN + 1e-3f, b.handDrop < BodyParams.HAND_DROP_MAX - 1e-3f) { onEvent(JcEvent.BodyStep(BodyField.HAND_DROP, it)) }
        StepRow(R.drawable.ic_peak, rs(R.string.menu_peak), rs(R.string.val_above_eye, cm(b.peakRise)), "peak", b.peakRise > BodyParams.PEAK_MIN + 1e-3f, b.peakRise < BodyParams.PEAK_MAX - 1e-3f) { onEvent(JcEvent.BodyStep(BodyField.PEAK, it)) }
        StepRow(R.drawable.ic_distance, rs(R.string.menu_z), rs(R.string.val_cm, cm(b.zFront)), "z_front", b.zFront > BodyParams.Z_FRONT_MIN + 1e-3f, b.zFront < BodyParams.Z_FRONT_MAX - 1e-3f) { onEvent(JcEvent.BodyStep(BodyField.Z_FRONT, it)) }
        StepRow(R.drawable.ic_scarf, rs(R.string.menu_vt), rs(R.string.val_vt, b.vt), "vt", b.vt > BodyParams.VT_MIN + 1e-3f, b.vt < BodyParams.VT_MAX - 1e-3f) { onEvent(JcEvent.BodyStep(BodyField.VT, it)) }
        SegRow(R.drawable.ic_slow, rs(R.string.menu_demo_slow), listOf("slow_03" to "0.3×", "slow_05" to "0.5×", "slow_1" to "1×"), state.settings.demoSlow.ordinal) { onEvent(JcEvent.DemoSlowSelected(DemoSlow.entries[it])) }
        SegRow(R.drawable.ic_count, rs(R.string.menu_set), listOf("set_6" to "6", "set_10" to "10", "set_20" to "20", "set_inf" to "∞"), state.setSize.ordinal) { onEvent(JcEvent.SetSizeSelected(SetSize.entries[it])) }
        SegRow(R.drawable.ic_beat, rs(R.string.menu_beat), listOf("beat_on" to rs(R.string.on), "beat_off" to rs(R.string.off)), if (state.settings.muted) 1 else 0) { onEvent(JcEvent.MuteSet(it == 1)) }
        SegRow(R.drawable.ic_timer, rs(R.string.menu_dwell), listOf("dwell_10" to "1.0 s", "dwell_12" to "1.2 s", "dwell_15" to "1.5 s"), state.settings.dwell.ordinal) { onEvent(JcEvent.DwellSelected(DwellTime.entries[it])) }
        GazeRow(state, onEvent)
        SegRow(R.drawable.ic_auto, rs(R.string.menu_auto_demo), listOf("auto_on" to rs(R.string.on), "auto_off" to rs(R.string.off)), if (state.settings.autoDemo) 0 else 1) { onEvent(JcEvent.AutoDemoSet(it == 0)) }
        val langSel = when (state.settings.language) { LanguagePref.ZH -> 0; LanguagePref.EN -> 1; LanguagePref.SYSTEM -> if (zh) 0 else 1 }
        SegRow(R.drawable.ic_globe, rs(R.string.menu_language), listOf("lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en)), langSel) { onEvent(JcEvent.LanguageSelected(if (it == 0) LanguagePref.ZH else LanguagePref.EN)) }
        // re-align, quit, close
        Row(modifier = Modifier.fillMaxWidth().height(ROW), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WordButton("menu_realign", rs(R.string.action_realign), { onEvent(JcEvent.RealignClicked) }, minWidth = 140.dp, height = 44.dp, iconRes = R.drawable.ic_target, enabled = state.frame != null)
            WordButton("menu_exit", rs(R.string.action_quit), { onEvent(JcEvent.ExitClicked) }, minWidth = 100.dp, height = 44.dp, iconRes = R.drawable.ic_exit, danger = true)
            Spacer(modifier = Modifier.weight(1f))
            Key("menu_close", R.drawable.ic_close, rs(R.string.action_more)) { onEvent(JcEvent.MoreClicked) }
        }
        // spike record (device spike checklist)
        Row(modifier = Modifier.fillMaxWidth().height(ROW), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painter = painterResource(R.drawable.ic_flask), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Text(text = rs(R.string.menu_spike), color = PicoTheme.colorScheme.labelPrimary, style = JcType.label, maxLines = 1, modifier = Modifier.weight(1f))
            WordButton("menu_spike", rs(R.string.menu_spike), { onEvent(JcEvent.SpikeOpen) }, minWidth = 120.dp, height = 44.dp)
        }
    }
}

private val ROW: Dp = 48.dp
private val CONTROL_W: Dp = 220.dp

private fun cm(m: Float): Int = (m * 100f).roundToInt()

@Composable
private fun Label(icon: Int, text: String, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = JcType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A stepper row: icon key "-", the value, icon key "+" (the design's fallback for the stepper: the SpatialUI `Stepper`
 * draws its - / + in a pale grey that reads below 3 : 1 on the light glass; the keys use the PicoTheme fill + white).
 */
@Composable
private fun StepRow(icon: Int, label: String, value: String, name: String, canDown: Boolean, canUp: Boolean, onStep: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(ROW), verticalAlignment = Alignment.CenterVertically) {
        Label(icon, label, Modifier.weight(1f))
        Row(modifier = Modifier.width(CONTROL_W), verticalAlignment = Alignment.CenterVertically) {
            Key("${name}_down", R.drawable.ic_minus, "-", enabled = canDown) { onStep(-1) }
            Text(text = value, color = PicoTheme.colorScheme.labelPrimary, style = JcType.figure, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Key("${name}_up", R.drawable.ic_plus, "+", enabled = canUp) { onStep(1) }
        }
    }
}

@Composable
private fun SegRow(icon: Int, label: String, items: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(ROW), verticalAlignment = Alignment.CenterVertically) {
        Label(icon, label, Modifier.weight(1f))
        Segments(items, selected, onSelect, width = CONTROL_W, height = ROW)
    }
}

/** Gaze source: "Eye tracking" + check, or "Head direction" + Ask again / Open settings (S13, design row 10). */
@Composable
private fun GazeRow(state: JcUiState, onEvent: (JcEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(ROW), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(R.drawable.ic_eye, rs(R.string.menu_gaze), Modifier.weight(1f))
        if (state.headFallback) {
            Text(text = rs(R.string.gaze_head), color = PicoTheme.colorScheme.labelSecondary, style = JcType.label, maxLines = 1)
            WordButton("gaze_ask", if (state.eyePermanent) rs(R.string.action_open_settings) else rs(R.string.action_ask_again), { onEvent(JcEvent.EyeAskAgain) }, minWidth = 96.dp, height = 44.dp)
        } else {
            Text(text = rs(R.string.gaze_eye), color = PicoTheme.colorScheme.labelPrimary, style = JcType.label, maxLines = 1)
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.passable)
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

/** K2: three switches, the current parameters and "Save record" (local + logcat `JC-SPIKE`). */
@Composable
private fun SpikePage(state: JcUiState, onEvent: (JcEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(ROW), verticalAlignment = Alignment.CenterVertically) {
        Text(text = rs(R.string.spike_title), color = PicoTheme.colorScheme.labelPrimary, style = JcType.title, maxLines = 1, modifier = Modifier.weight(1f))
        Key("spike_close", R.drawable.ic_close, rs(R.string.action_more)) { onEvent(JcEvent.MoreClicked) }
    }
    val labels = listOf(R.string.spike_1, R.string.spike_2, R.string.spike_3)
    for ((i, id) in labels.withIndex()) {
        Row(modifier = Modifier.fillMaxWidth().height(ROW), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(id), color = PicoTheme.colorScheme.labelPrimary, style = JcType.label, maxLines = 1, modifier = Modifier.weight(1f))
            Switch(checked = state.spikeChecks[i], onCheckedChange = { onEvent(JcEvent.SpikeCheck(i, it)) }, modifier = Modifier.probe("spike_$i") { onEvent(JcEvent.SpikeCheck(i, !state.spikeChecks[i])) })
        }
    }
    val b = state.body
    Text(
        text = rs(R.string.spike_params, cm(b.shoulder), cm(b.peakRise), cm(b.zFront), b.vt),
        color = PicoTheme.colorScheme.labelSecondary,
        style = JcType.label,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().height(ROW),
    )
    WordButton("spike_save", rs(R.string.action_save_spike), { onEvent(JcEvent.SpikeSave) }, primary = true, minWidth = 160.dp)
}
