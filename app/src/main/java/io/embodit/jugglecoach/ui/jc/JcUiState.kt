package io.embodit.jugglecoach.ui.jc

import io.embodit.jugglecoach.domain.juggle.BodyFrame
import io.embodit.jugglecoach.domain.juggle.Course
import io.embodit.jugglecoach.domain.model.BlockReason
import io.embodit.jugglecoach.domain.model.BodyParams
import io.embodit.jugglecoach.domain.model.DemoSlow
import io.embodit.jugglecoach.domain.model.DwellTime
import io.embodit.jugglecoach.domain.model.LanguagePref
import io.embodit.jugglecoach.domain.model.ScarfSpeed
import io.embodit.jugglecoach.domain.model.SetSize
import io.embodit.jugglecoach.domain.model.Settings
import io.embodit.jugglecoach.domain.model.Vec3

/** Base states of the design (`stateModel.base`). */
enum class Phase(val id: String) {
    STARTING("S00"),
    WELCOME("S01"),
    PICKER("S02"),
    ALIGNING("S03"),
    DEMO("S04"),
    COUNT_IN("S05a"),
    FOLLOWING("S05"),
    PAUSED("S06"),
    SET_DONE("S07"),
    BLOCKED("S11");

    /** Phases with the body-frame content in the room (paths, discs, scarves). */
    val practising: Boolean
        get() = this == DEMO || this == COUNT_IN || this == FOLLOWING || this == PAUSED || this == SET_DONE
}

/** What the More panel shows: the 14 menu rows or the spike checklist (K2). */
enum class MenuPage { MENU, SPIKE }

/** One-shot hint lines that cover the state's own hint for a few seconds (design `copy.hints`). */
enum class HintOverride { LEGEND, THREE_SCARVES, HEAD_FALLBACK }

/** Gaze targets (design `input.dwell.targets`). */
object DwellTarget {
    const val LEFT_DISC = "disc_left"
    const val RIGHT_DISC = "disc_right"
    const val WELCOME = "welcome_go"

    fun card(course: Course) = "card_${course.number}"

    fun courseOf(target: String): Course? = if (target.startsWith("card_")) Course.of(target.removePrefix("card_").toIntOrNull() ?: -1) else null
}

data class JcUiState(
    val phase: Phase = Phase.STARTING,
    val course: Course = Course.P1,
    /** Card highlighted on the picker (the neighbour when coming from the previous / next keys). */
    val pickerCourse: Course = Course.P1,
    val settings: Settings = Settings(),
    /** Body frame of the last alignment; null until the first one. */
    val frame: BodyFrame? = null,
    /** Bumped on every new frame (the scene slides the content to it). */
    val frameSerial: Int = 0,
    /** S03: the phase to return to after re-aligning (null = go on to the demo of [course]). */
    val alignReturn: Phase? = null,
    /** S03 started from the picker / start: the panel stays in front while sampling. */
    val alignFromFront: Boolean = true,
    val alignAttempt: Int = 1,
    val alignSerial: Int = 0,
    val demoPlaying: Boolean = false,
    val demoSerial: Int = 0,
    val runSerial: Int = 0,
    /** Count at the start of the current run (a resumed set keeps counting). */
    val countBase: Int = 0,
    /** Count-in beat 0..3 now (the panel shows 4 - beat), null outside the count-in. */
    val countInBeat: Int? = null,
    val count: Int = 0,
    val menuOpen: Boolean = false,
    val menuPage: MenuPage = MenuPage.MENU,
    val drifted: Boolean = false,
    val dwellTarget: String? = null,
    /** 0..1 rim progress of [dwellTarget] (steps of 0.1). */
    val dwellProgress: Float = 0f,
    /** S13: no eye tracking (refused / not supported); the head direction aims. */
    val headFallback: Boolean = false,
    val eyePermanent: Boolean = false,
    /** Transient fallback: eyes in use but no valid sample just now (gaze dot amber). */
    val gazeAmber: Boolean = false,
    val blockReason: BlockReason? = null,
    val firstPicker: Boolean = true,
    val hintOverride: HintOverride? = null,
    val lastSets: Map<Int, Int> = emptyMap(),
    /** Spike build behaviour (K1): lessons 2 - 6 greyed, "spike" tag on the chip. */
    val spike: Boolean = false,
    val spikeChecks: List<Boolean> = listOf(false, false, false),
    // ---- debug holds (QA hooks)
    val debugPhase: Float? = null,
    val debugDwell: Float? = null,
    val debugHold: Boolean = false,
    val debugCountInBeat: Int? = null,
    val debugAnchor: BodyFrame? = null,
    val debugPitchDeg: Float? = null,
    val testTexture: Boolean = false,
) {
    val body: BodyParams
        get() = settings.body

    val speedSegment: ScarfSpeed?
        get() = ScarfSpeed.of(settings.body.vt)

    val setSize: SetSize
        get() = settings.setSize

    val english: Boolean
        get() = settings.language == LanguagePref.EN

    /** QA name: base[+overlay][:course] (design `stateModel.naming`). */
    val qaName: String
        get() {
            val sb = StringBuilder(if (spike && phase == Phase.FOLLOWING) "K1" else phase.id)
            if (dwellTarget != null && dwellProgress > 0f) sb.append("+S08")
            if (menuOpen) sb.append(if (menuPage == MenuPage.SPIKE) "+K2" else "+S09")
            if (drifted) sb.append("+S10")
            if (english) sb.append("+S12")
            if (headFallback && phase != Phase.STARTING && phase != Phase.WELCOME) sb.append("+S13")
            if (phase.practising || phase == Phase.ALIGNING) sb.append(":${course.number}")
            return sb.toString()
        }
}

/** Panel, scene and debug inputs. Every change of the model goes through [JcViewModel.onEvent]. */
sealed interface JcEvent {
    // ---- panel
    data object WelcomeGo : JcEvent
    data class CardClicked(val course: Course, val source: String) : JcEvent
    data object MainClicked : JcEvent
    data object PrevClicked : JcEvent
    data object NextClicked : JcEvent
    data object WatchClicked : JcEvent
    data object MoreClicked : JcEvent
    data object TappedOutside : JcEvent
    data class SpeedSelected(val speed: ScarfSpeed) : JcEvent
    data class MuteSet(val muted: Boolean) : JcEvent
    data class BodyStep(val what: BodyField, val steps: Int) : JcEvent
    data class DemoSlowSelected(val v: DemoSlow) : JcEvent
    data class SetSizeSelected(val v: SetSize) : JcEvent
    data class DwellSelected(val v: DwellTime) : JcEvent
    data class AutoDemoSet(val on: Boolean) : JcEvent
    data class LanguageSelected(val v: LanguagePref) : JcEvent
    data object EyeAskAgain : JcEvent
    data object RealignClicked : JcEvent
    data object ExitClicked : JcEvent
    data object RetryClicked : JcEvent
    data object SpikeOpen : JcEvent
    data class SpikeCheck(val index: Int, val on: Boolean) : JcEvent
    data object SpikeSave : JcEvent

    // ---- scene
    data object PanelsReady : JcEvent
    data class HeadSample(val position: Vec3, val yaw: Float) : JcEvent
    data class Aligned(val frame: BodyFrame) : JcEvent
    data object AlignRetry : JcEvent
    data object AlignFailed : JcEvent
    data class DemoFinished(val serial: Int) : JcEvent
    data class CountInDone(val serial: Int) : JcEvent
    data class CountInBeat(val beat: Int?) : JcEvent
    data class Counted(val serial: Int, val count: Int) : JcEvent
    data class SetComplete(val serial: Int) : JcEvent
    data class DriftChanged(val drifted: Boolean) : JcEvent
    data class Reframed(val frame: BodyFrame) : JcEvent
    data class DwellChanged(val target: String?, val progress: Float) : JcEvent
    data class DwellFired(val target: String, val source: String) : JcEvent
    data class BlockChanged(val reason: BlockReason?) : JcEvent
    data class EyeState(val permanentUnavailable: Boolean, val amber: Boolean) : JcEvent
    data class EyePermission(val granted: Boolean, val permanent: Boolean) : JcEvent
    data object WentBackground : JcEvent
    data object CameForeground : JcEvent

    // ---- debug
    data class DebugState(val id: String) : JcEvent
    data class DebugCourse(val course: Course) : JcEvent
    data class DebugVt(val vt: Float) : JcEvent
    data class DebugPhase(val phase: Float?) : JcEvent
    data class DebugDwell(val seconds: Float?) : JcEvent
    data class DebugDrift(val on: Boolean) : JcEvent
    data class DebugAnchor(val frame: BodyFrame?) : JcEvent
    data class DebugBody(val body: BodyParams) : JcEvent
    data class DebugBlocked(val on: Boolean) : JcEvent
    data class DebugSpike(val on: Boolean) : JcEvent
    data class DebugPitch(val deg: Float?) : JcEvent
    data class DebugHeadFallback(val on: Boolean) : JcEvent
    data object DebugRelease : JcEvent
    data object DebugLegendReset : JcEvent
    data object DebugClear : JcEvent
    data class DebugTestTexture(val on: Boolean) : JcEvent
}

/** Menu steppers (design `panels.moreMenu` rows 1 - 5). */
enum class BodyField { SHOULDER, HAND_DROP, PEAK, Z_FRONT, VT }

/** One-shot effects for the screen. */
sealed interface JcEffect {
    data object ExitApp : JcEffect
    data object RequestEyePermission : JcEffect
    data object OpenAppSettings : JcEffect
}
