package io.embodit.jugglecoach.ui.jc.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import io.embodit.jugglecoach.ui.jc.JcEvent
import io.embodit.jugglecoach.ui.jc.JcUiState
import java.util.concurrent.ConcurrentHashMap

/**
 * Seams for QA's adb harness. Nothing here is product UI and nothing here does anything by itself:
 * the hooks are only driven by the debug-build receiver (`src/debug`). In a release build no receiver
 * exists, [JcProbe.enabled] stays false and the bounds modifiers are no-ops.
 */
object JcProbe {
    @Volatile var enabled: Boolean = false

    data class ControlBounds(val cx: Float, val cy: Float, val w: Float, val h: Float)

    private val controls = ConcurrentHashMap<String, ConcurrentHashMap<String, ControlBounds>>()
    private val panelSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    fun report(panel: String, name: String, bounds: ControlBounds, panelWidth: Int, panelHeight: Int) {
        controls.getOrPut(panel) { ConcurrentHashMap() }[name] = bounds
        panelSizes[panel] = panelWidth to panelHeight
    }

    fun forget(panel: String, name: String) {
        controls[panel]?.remove(name)
    }

    fun controlsOf(panel: String): Map<String, ControlBounds> = controls[panel]?.toSortedMap() ?: emptyMap()

    fun sizeOf(panel: String): Pair<Int, Int>? = panelSizes[panel]

    /** What the screen exposes to the debug receiver while it is composed. */
    interface Host {
        fun dispatch(event: JcEvent)

        fun uiState(): JcUiState

        fun dumpJson(): String

        fun scene(): SceneProbe?

        /** Presses a panel control by its stable name; false when no such control is composed. */
        fun pressControl(name: String): Boolean
    }

    interface SceneProbe {
        fun poseLine(): String

        fun panelLines(): List<String>

        fun geomLine(): String

        fun clockLine(): String
    }

    @Volatile var host: Host? = null

    /** Panel controls by name (registered by `Modifier.probe`): the debug command `ui <name>` presses one. */
    private val clicks = ConcurrentHashMap<String, () -> Unit>()

    fun registerClick(name: String, onClick: () -> Unit) {
        clicks[name] = onClick
    }

    fun unregisterClick(name: String) {
        clicks.remove(name)
    }

    fun click(name: String): Boolean {
        val c = clicks[name] ?: return false
        c()
        return true
    }
}

/** Id of the attachment panel whose content is being composed. */
val LocalProbePanel = compositionLocalOf { "" }

/** Registers the on-panel bounds of a control under a stable [name]. A no-op unless the app is debuggable. */
@Composable
fun Modifier.probe(name: String, onClick: (() -> Unit)? = null): Modifier {
    if (!JcProbe.enabled) return this
    val panel = LocalProbePanel.current
    DisposableEffect(panel, name, onClick) {
        if (onClick != null) JcProbe.registerClick(name, onClick)
        onDispose {
            JcProbe.forget(panel, name)
            if (onClick != null) JcProbe.unregisterClick(name)
        }
    }
    return this.onGloballyPositioned { c ->
        if (c.isAttached) {
            val root = c.findRootCoordinates()
            val topLeft = root.localPositionOf(c, Offset.Zero)
            val bottomRight = root.localPositionOf(c, Offset(c.size.width.toFloat(), c.size.height.toFloat()))
            JcProbe.report(
                panel,
                name,
                JcProbe.ControlBounds((topLeft.x + bottomRight.x) / 2f, (topLeft.y + bottomRight.y) / 2f, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                root.size.width,
                root.size.height,
            )
        }
    }
}
