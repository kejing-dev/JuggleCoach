package io.embodit.jugglecoach.ui.jc.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.jugglecoach.R
import io.embodit.jugglecoach.domain.juggle.Course
import io.embodit.jugglecoach.domain.juggle.Lesson
import io.embodit.jugglecoach.domain.model.ScarfSpeed
import io.embodit.jugglecoach.ui.jc.HintOverride
import io.embodit.jugglecoach.ui.jc.JcEvent
import io.embodit.jugglecoach.ui.jc.JcUiState
import io.embodit.jugglecoach.ui.jc.Phase
import io.embodit.jugglecoach.ui.jc.debug.probe
import io.embodit.jugglecoach.ui.jc.scene.PanelSpec
import io.embodit.jugglecoach.ui.theme.JcColors
import java.util.Locale

/**
 * The standing control panel (design `panels.controlPanel`, 440 x 252 dp at 0.75): lesson name + counter chip + sound
 * switch + More; scarf speed segments + count-in digit / "Watch once"; previous lesson / main / next lesson; a two-line
 * hint. While following, everything needed is in the 3D content; this panel is the backup.
 */
@Composable
internal fun ControlPanel(state: JcUiState, filled: Boolean, onEvent: (JcEvent) -> Unit) {
    val (w, h) = PanelSpec.CONTROL_SIZE
    Glass(w.dp, h.dp, corner = 28.dp) {
        if (!filled) return@Glass
        val phase = state.phase
        val busy = phase == Phase.STARTING
        val blocked = phase == Phase.BLOCKED
        val aligning = phase == Phase.ALIGNING
        val browse = (phase == Phase.DEMO || phase == Phase.PAUSED || phase == Phase.SET_DONE) && !state.drifted
        // row 1: lesson name, counter chip, sound, more (48)
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = if (busy || blocked) rs(R.string.title_app) else courseName(state.course)
            Text(text = title, color = PicoTheme.colorScheme.labelPrimary, style = JcType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            chipText(state)?.let { ValueChip(it) }
            if (state.spike && phase.practising) Text(text = rs(R.string.chip_spike), color = PicoTheme.colorScheme.labelSecondary, style = JcType.chip, maxLines = 1)
            Icon(painter = painterResource(R.drawable.ic_sound), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Switch(
                checked = !state.settings.muted,
                onCheckedChange = { on -> onEvent(JcEvent.MuteSet(!on)) },
                modifier = Modifier.probe("sound") { onEvent(JcEvent.MuteSet(!state.settings.muted)) },
            )
            Key("more", R.drawable.ic_more, rs(R.string.action_more), enabled = !blocked && !busy && !aligning) { onEvent(JcEvent.MoreClicked) }
        }
        // row 2: scarf speed + count-in digit or "Watch once" (56)
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedSegments(state, enabled = !busy && !blocked && !aligning, onEvent = onEvent)
            Spacer(modifier = Modifier.weight(1f))
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(32.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(32.dp))
                phase == Phase.COUNT_IN -> Box(modifier = Modifier.width(60.dp).height(56.dp), contentAlignment = Alignment.Center) { Text(text = countInDigit(state), color = PicoTheme.colorScheme.labelPrimary, style = JcType.countdown, maxLines = 1) }
                browse && !(phase == Phase.DEMO && state.demoPlaying) -> WordButton("watch", rs(R.string.action_watch), { onEvent(JcEvent.WatchClicked) }, minWidth = 96.dp)
                else -> Unit
            }
        }
        // row 3: previous / main / next (56)
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("prev", R.drawable.ic_prev, rs(R.string.action_prev), enabled = browse && !state.spike && state.course.previous != null) { onEvent(JcEvent.PrevClicked) }
            val (label, icon, enabled) = mainAction(state)
            MainButton("main", label, icon, enabled, Modifier.weight(1f)) { onEvent(JcEvent.MainClicked) }
            Key("next", R.drawable.ic_next, rs(R.string.action_next), enabled = browse && !state.spike && state.course.next != null) { onEvent(JcEvent.NextClicked) }
        }
        // row 4: hint, at most two lines (36)
        HintLine(state)
    }
}

/** Lesson name in the app's language. */
@Composable
internal fun courseName(c: Course): String =
    rs(
        when (c) {
            Course.P1 -> R.string.course_1
            Course.P2 -> R.string.course_2
            Course.P3 -> R.string.course_3
            Course.P4 -> R.string.course_4
            Course.P5 -> R.string.course_5
            Course.P6 -> R.string.course_6
        }
    )

@Composable
internal fun courseDesc(c: Course): String =
    rs(
        when (c) {
            Course.P1 -> R.string.desc_1
            Course.P2 -> R.string.desc_2
            Course.P3 -> R.string.desc_3
            Course.P4 -> R.string.desc_4
            Course.P5 -> R.string.desc_5
            Course.P6 -> R.string.desc_6
        }
    )

/** [Floaty | Normal | Fast] (0.7 / 0.9 / 1.1 m/s); none highlighted when the menu fine-tune sits between them. */
@Composable
internal fun SpeedSegments(state: JcUiState, enabled: Boolean, onEvent: (JcEvent) -> Unit) {
    Segments(
        items = listOf("speed_floaty" to rs(R.string.speed_floaty), "speed_normal" to rs(R.string.speed_normal), "speed_fast" to rs(R.string.speed_fast)),
        selected = state.speedSegment?.ordinal,
        onSelect = { onEvent(JcEvent.SpeedSelected(ScarfSpeed.entries[it])) },
        // 264 (design 220): "Normal" needs 88 dp per item at labelLarge
        width = 264.dp,
        enabled = enabled,
    )
}

@Composable
private fun chipText(s: JcUiState): String? {
    val n = s.setSize.n
    return when (s.phase) {
        Phase.DEMO -> if (s.demoPlaying) rs(R.string.chip_demo, demoSlowText(s)) else null
        Phase.COUNT_IN, Phase.FOLLOWING, Phase.PAUSED -> if (n == null) rs(R.string.counter_inf, s.count) else rs(R.string.counter, s.count, n)
        Phase.SET_DONE -> rs(R.string.chip_done, n ?: s.count)
        else -> null
    }
}

private fun demoSlowText(s: JcUiState): String {
    val k = s.settings.demoSlow.k
    return if (k >= 1f) "1×" else String.format(Locale.US, "%.1f×", k)
}

/** The panel's copy of the count-in digit (4 3 2 1), from the beat the scene reports. */
private fun countInDigit(s: JcUiState): String = (s.debugCountInBeat ?: s.countInBeat)?.let { (Lesson.COUNT_IN_BEATS - it).toString() } ?: ""

/** Label, icon and enabled state of the main button by state (design `panels.controlPanel` row 3). */
@Composable
private fun mainAction(s: JcUiState): Triple<String, Int, Boolean> =
    when {
        s.drifted && s.phase.practising -> Triple(rs(R.string.action_bring_here), R.drawable.ic_target, true)
        s.phase == Phase.ALIGNING -> Triple(rs(R.string.action_aligning), R.drawable.ic_target, false)
        s.phase == Phase.DEMO && s.demoPlaying -> Triple(rs(R.string.action_skip_start), R.drawable.ic_play, true)
        s.phase == Phase.DEMO -> Triple(rs(R.string.action_start), R.drawable.ic_play, true)
        s.phase == Phase.COUNT_IN || s.phase == Phase.FOLLOWING -> Triple(rs(R.string.action_pause), R.drawable.ic_pause, true)
        s.phase == Phase.PAUSED -> Triple(rs(R.string.action_resume), R.drawable.ic_play, true)
        s.phase == Phase.SET_DONE -> Triple(rs(R.string.action_one_more), R.drawable.ic_replay, true)
        else -> Triple(rs(R.string.action_start), R.drawable.ic_play, false)
    }

/** Hint line (design `copy.hints`); the one-shot overrides (legend, three scarves, head fallback) cover it. */
@Composable
private fun HintLine(state: JcUiState) {
    val secondary = PicoTheme.colorScheme.labelSecondary
    val legend = state.hintOverride == HintOverride.LEGEND
    val text =
        when {
            state.hintOverride == HintOverride.HEAD_FALLBACK -> rs(R.string.hint_s13)
            legend -> null
            state.hintOverride == HintOverride.THREE_SCARVES -> rs(R.string.hint_three)
            state.phase == Phase.BLOCKED -> rs(R.string.hint_s11)
            state.drifted -> rs(R.string.hint_s10)
            state.phase == Phase.STARTING -> rs(R.string.hint_s00)
            state.phase == Phase.ALIGNING -> if (state.alignAttempt > 1) rs(R.string.hint_s03_retry) else rs(R.string.hint_s03)
            state.phase == Phase.DEMO -> if (state.demoPlaying) rs(R.string.hint_s04) else rs(R.string.hint_s04_ready)
            state.phase == Phase.COUNT_IN -> rs(R.string.hint_s05a)
            state.phase == Phase.FOLLOWING -> rs(R.string.hint_s05)
            state.phase == Phase.PAUSED -> rs(R.string.hint_s06)
            state.phase == Phase.SET_DONE -> state.course.next?.let { rs(R.string.hint_s07, courseName(it)) } ?: rs(R.string.hint_s07_last)
            else -> ""
        }
    Row(modifier = Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (legend) {
            // legend line: a 12 dp colour dot before each half, the words carry the meaning
            Box(modifier = Modifier.padding(top = 3.dp)) { Dot(JcColors.ScarfCoral) }
            Text(text = rs(R.string.legend_right), color = secondary, style = JcType.hint, maxLines = 1)
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.padding(top = 3.dp)) { Dot(JcColors.ScarfSky) }
            Text(text = rs(R.string.legend_left), color = secondary, style = JcType.hint, maxLines = 1)
        } else {
            Text(text = text ?: "", color = if (state.phase == Phase.BLOCKED) PicoTheme.colorScheme.error else secondary, style = JcType.hint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}
