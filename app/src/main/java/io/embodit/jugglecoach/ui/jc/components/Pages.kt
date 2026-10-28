package io.embodit.jugglecoach.ui.jc.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.jugglecoach.R
import io.embodit.jugglecoach.domain.juggle.Course
import io.embodit.jugglecoach.domain.juggle.Lesson
import io.embodit.jugglecoach.domain.model.BodyParams
import io.embodit.jugglecoach.domain.model.Hand
import io.embodit.jugglecoach.ui.jc.DwellTarget
import io.embodit.jugglecoach.ui.jc.JcEvent
import io.embodit.jugglecoach.ui.jc.JcUiState
import io.embodit.jugglecoach.ui.jc.debug.probe
import io.embodit.jugglecoach.ui.jc.scene.PanelSpec
import io.embodit.jugglecoach.ui.theme.JcColors

/**
 * Welcome (S01, design `panels.welcomePage`, 440 x 300 dp, in front at 0.7 m): title, three lines, and "OK, let's go"
 * (a press or 1.2 s of gaze; it asks for eye tracking next). The button sits where [PanelSpec.WELCOME_BUTTON_TOP] says.
 */
@Composable
internal fun WelcomePage(state: JcUiState, filled: Boolean, onEvent: (JcEvent) -> Unit) {
    val (w, h) = PanelSpec.WELCOME_SIZE
    Glass(w.dp, h.dp, corner = 28.dp, spacing = 0.dp) {
        if (!filled) return@Glass
        Row(modifier = Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.welcome_title), color = PicoTheme.colorScheme.labelPrimary, style = JcType.title, maxLines = 1)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WelcomeLine(R.drawable.ic_eye, rs(R.string.welcome_1))
            WelcomeLine(R.drawable.ic_eye_dwell, rs(R.string.welcome_2))
            WelcomeLine(R.drawable.ic_scarf, rs(R.string.welcome_3))
        }
        Spacer(modifier = Modifier.height(12.dp))
        val progress = if (state.dwellTarget == DwellTarget.WELCOME) state.dwellProgress else 0f
        Box(modifier = Modifier.fillMaxWidth().height(PanelSpec.WELCOME_BUTTON_H.dp), contentAlignment = Alignment.CenterEnd) {
            WordButton("welcome_go", rs(R.string.action_welcome_go), { onEvent(JcEvent.WelcomeGo) }, modifier = Modifier.fillMaxWidth(), primary = true, height = PanelSpec.WELCOME_BUTTON_H.dp, iconRes = R.drawable.ic_play)
            DwellRing(progress, Modifier.padding(end = 16.dp))
        }
    }
}

@Composable
private fun WelcomeLine(icon: Int, text: String) {
    Row(modifier = Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = JcType.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The lesson picker (S02, design `panels.coursePicker`, here 480 x 560 dp, in front at 0.7 m): title + More, six cards
 * in two columns (each a SpatialUI button and a dwell target), the first-time hint, the scarf speed segments. Spike
 * builds grey lessons 2 - 6.
 */
@Composable
internal fun CoursePicker(state: JcUiState, filled: Boolean, onEvent: (JcEvent) -> Unit) {
    val (w, h) = PanelSpec.PICKER_SIZE
    Glass(w.dp, h.dp, corner = 28.dp, spacing = 8.dp) {
        if (!filled) return@Glass
        Row(modifier = Modifier.fillMaxWidth().height(PanelSpec.TITLE_H.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = rs(R.string.picker_title), color = PicoTheme.colorScheme.labelPrimary, style = JcType.title, maxLines = 1, modifier = Modifier.weight(1f))
            Key("more", R.drawable.ic_more, rs(R.string.action_more)) { onEvent(JcEvent.MoreClicked) }
        }
        for (row in 0 until 3) {
            Row(modifier = Modifier.fillMaxWidth().height(PanelSpec.CARD_H.dp), horizontalArrangement = Arrangement.spacedBy(PanelSpec.CARD_GAP.dp)) {
                for (col in 0 until 2) {
                    val c = Course.entries[row * 2 + col]
                    CourseCard(state, c, onEvent)
                }
            }
        }
        Text(
            text = if (state.firstPicker) rs(R.string.hint_s02_first) else rs(R.string.hint_s02),
            color = PicoTheme.colorScheme.labelSecondary,
            style = JcType.hint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(36.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) { SpeedSegments(state, enabled = true, onEvent = onEvent) }
    }
}

@Composable
private fun CourseCard(state: JcUiState, c: Course, onEvent: (JcEvent) -> Unit) {
    val locked = state.spike && c != Course.P1
    val chosen = c == state.pickerCourse
    val progress = if (state.dwellTarget == DwellTarget.card(c)) state.dwellProgress else 0f
    val container = if (chosen) PicoTheme.colorScheme.interaction.copy(alpha = 0.22f) else PicoTheme.colorScheme.fillTertiary
    val onClick = { if (!locked) onEvent(JcEvent.CardClicked(c, "tap")) }
    Button(
        onClick = onClick,
        modifier = Modifier.width(PanelSpec.CARD_W.dp).height(PanelSpec.CARD_H.dp).alpha(if (locked) 0.45f else 1f).probe("card_${c.number}", onClick),
        size = ButtonDefaults.Regular.copy(height = PanelSpec.CARD_H.dp, minWidth = PanelSpec.CARD_W.dp),
        shape = RoundedCornerShape(20.dp),
        enabled = !locked,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = PicoTheme.colorScheme.labelPrimary),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = courseName(c), color = PicoTheme.colorScheme.labelPrimary, style = JcType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = courseDesc(c), color = PicoTheme.colorScheme.labelSecondary, style = JcType.chip, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourseThumb(c)
                val last = state.lastSets[c.number] ?: 0
                val chip = if (locked) rs(R.string.chip_after_spike) else if (last > 0) rs(R.string.chip_last, last) else ""
                Text(text = chip, color = PicoTheme.colorScheme.labelSecondary, style = JcType.chip, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                DwellRing(progress)
            }
        }
    }
}

/** The lesson's paths as a small drawing (right orange, left blue), from the same geometry as the 3D paths. */
@Composable
private fun CourseThumb(c: Course) {
    val lesson = Lesson(c, BodyParams())
    Canvas(modifier = Modifier.width(44.dp).height(28.dp)) {
        val sx = size.width / 0.70f
        val sy = size.height / 0.62f
        fun map(x: Float, y: Float) = Offset(size.width / 2f + x * sx, size.height * (0.20f / 0.62f + 0.04f) - y * sy)
        for ((h, path) in lesson.paths) {
            val color = if (h == Hand.RIGHT) JcColors.ScarfCoral else JcColors.ScarfSky
            val pts = path.flightPoints(16)
            val p = Path()
            pts.forEachIndexed { i, q -> map(q.x, q.y).let { o -> if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) } }
            drawPath(p, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawLine(color, map(path.hold.x, path.hold.y), map(path.release.x, path.release.y), strokeWidth = 1.dp.toPx())
        }
    }
}
