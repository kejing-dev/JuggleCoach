package io.embodit.jugglecoach.ui.jc.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.jugglecoach.ui.jc.JcEvent
import io.embodit.jugglecoach.ui.jc.JcUiState
import io.embodit.jugglecoach.ui.jc.debug.LocalProbePanel
import io.embodit.jugglecoach.ui.jc.scene.JcScene
import io.embodit.jugglecoach.ui.jc.scene.PanelSpec

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every
 * attachment panel is declared here once, with a fixed size, whether it is showing or not; the scene
 * parents each panel entity under its holder and shows or hides the holder.
 *
 * Window model: single panel with popup (design): one standing panel whose content changes (control, welcome, picker,
 * menu: each its own pooled panel, shown alone at the same place) and the blocked dialog as the only popup. No
 * WindowContainer, no planar window.
 */
@Composable
internal fun JcStage(state: JcUiState, scene: JcScene, onEvent: (JcEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                },
        attachments = {
            AttachmentPanel(PanelSpec.CONTROL) {
                PoolPanel(PanelSpec.CONTROL, scene, refresh = listOf(state.settings.language, state.phase, state.drifted)) { inView -> ControlPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.WELCOME) {
                PoolPanel(PanelSpec.WELCOME, scene, refresh = state.settings.language) { inView -> WelcomePage(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.PICKER) {
                PoolPanel(PanelSpec.PICKER, scene, refresh = listOf(state.settings.language, state.firstPicker)) { inView -> CoursePicker(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, refresh = listOf(state.settings.language, state.menuOpen, state.menuPage)) { inView -> MoreMenu(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DIALOG) {
                PoolPanel(PanelSpec.DIALOG, scene, refresh = state.blockReason) { inView -> BlockedDialog(reason = if (inView) state.blockReason else null, onEvent = onEvent) }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled panel must draw
 * its content only then (what it draws while parked out of sight stays broken on the emulator). On the idle
 * emulator a panel can keep a half-recorded first frame (glass cut off, black below): the content is composed
 * afresh 1.5, 4 and 8 s after the panel comes into view or [refresh] changes (factory finding on bianzhong).
 */
@Composable
private fun PoolPanel(id: String, scene: JcScene, refresh: Any? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    var gen by remember { mutableIntStateOf(0) }
    LaunchedEffect(inView, refresh) {
        if (!inView) return@LaunchedEffect
        for (wait in REFRESH_STEPS_MS) {
            delay(wait)
            gen++
        }
    }
    CompositionLocalProvider(LocalProbePanel provides id) { key(gen) { content(inView) } }
}

private val REFRESH_STEPS_MS = longArrayOf(1_500L, 2_500L, 4_000L)
