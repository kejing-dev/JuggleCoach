package io.embodit.jugglecoach.ui.jc

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.jugglecoach.BuildConfig
import io.embodit.jugglecoach.data.SettingsStore
import io.embodit.jugglecoach.domain.juggle.BodyFrame
import io.embodit.jugglecoach.domain.juggle.Course
import io.embodit.jugglecoach.domain.model.BlockReason
import io.embodit.jugglecoach.domain.model.BodyParams
import io.embodit.jugglecoach.domain.model.Settings
import io.embodit.jugglecoach.domain.model.Vec3
import io.embodit.jugglecoach.platform.JcGraph
import io.embodit.jugglecoach.platform.JcLog
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The whole state machine of the app (design `states`, `interactions`, `rules`). Panel controls, scene callbacks
 * (alignment, the lesson clock's milestones, drift, dwell, eye and head availability) and the debug hooks all arrive
 * through [onEvent]. Per-frame work never comes here: the scene reports milestones only. Main thread only.
 */
class JcViewModel(private val store: SettingsStore) : ViewModel() {

    private val _state: MutableStateFlow<JcUiState>
    val state: StateFlow<JcUiState>
    private val _effects = MutableSharedFlow<JcEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<JcEffect> = _effects

    private var headPos: Vec3? = null
    private var headYaw = 0f
    private var lastLogged = ""
    private var hintJob: Job? = null
    private var wasPractising: Phase? = null

    init {
        val settings = store.load()
        _state =
            MutableStateFlow(
                JcUiState(
                    settings = settings,
                    firstPicker = !store.welcomed,
                    lastSets = Course.entries.associate { it.number to store.lastSet(it.number) },
                    spike = BuildConfig.SPIKE,
                    headFallback = store.eyeAsked && !EyeCheck.granted(),
                    eyePermanent = store.eyePermanent,
                )
            )
        state = _state.asStateFlow()
        JcLog.i("SETTINGS $settings")
    }

    /** Eye permission state as the app sees it (the platform answers; tests see "granted"). */
    private object EyeCheck {
        fun granted(): Boolean = JcGraph.appContext?.let { io.embodit.jugglecoach.platform.EyePermissionActivity.granted(it) } ?: true
    }

    fun onEvent(e: JcEvent) {
        when (e) {
            is JcEvent.HeadSample -> {
                headPos = e.position
                headYaw = e.yaw
            }
            is JcEvent.CountInBeat -> if (_state.value.countInBeat != e.beat) set { it.copy(countInBeat = e.beat) }
            is JcEvent.DwellChanged -> {
                val s = _state.value
                if (s.dwellTarget != e.target || s.dwellProgress != e.progress) set { it.copy(dwellTarget = e.target, dwellProgress = e.progress) }
            }
            else -> {
                if (e !is JcEvent.Counted) JcLog.i("EVENT $e")
                handle(e)
            }
        }
        logState()
    }

    private fun handle(e: JcEvent) {
        val s = _state.value
        when (e) {
            // ---------------------------------------------------------------- start
            JcEvent.PanelsReady -> if (s.phase == Phase.STARTING) set { it.copy(phase = if (store.welcomed) Phase.PICKER else Phase.WELCOME) }
            is JcEvent.BlockChanged -> {
                val r = e.reason ?: return
                if (s.phase != Phase.BLOCKED) toBlocked(r)
            }
            JcEvent.RetryClicked -> if (s.phase == Phase.BLOCKED) set { it.copy(phase = Phase.STARTING, blockReason = null, menuOpen = false, drifted = false) }
            JcEvent.WelcomeGo -> {
                if (s.phase != Phase.WELCOME) return
                store.welcomed = true
                store.eyeAsked = true
                set { it.copy(phase = Phase.PICKER, firstPicker = true) }
                emit(JcEffect.RequestEyePermission)
            }
            is JcEvent.EyePermission -> {
                store.eyePermanent = e.permanent
                set { it.copy(headFallback = !e.granted, eyePermanent = e.permanent) }
                if (!e.granted) flashHint(HintOverride.HEAD_FALLBACK, HEAD_HINT_MS)
            }
            is JcEvent.EyeState -> {
                val asked = store.eyeAsked
                val fallback = e.permanentUnavailable && asked
                if (fallback && !s.headFallback) flashHint(HintOverride.HEAD_FALLBACK, HEAD_HINT_MS)
                if (fallback != s.headFallback || e.amber != s.gazeAmber) set { it.copy(headFallback = fallback, gazeAmber = e.amber) }
            }
            JcEvent.EyeAskAgain -> emit(if (s.eyePermanent) JcEffect.OpenAppSettings else JcEffect.RequestEyePermission)

            // ---------------------------------------------------------------- picker
            is JcEvent.CardClicked -> if (s.phase == Phase.PICKER && !s.menuOpen) chooseCourse(e.course)

            // ---------------------------------------------------------------- alignment
            is JcEvent.Aligned -> {
                if (s.phase != Phase.ALIGNING) return
                val back = s.alignReturn
                set { it.copy(frame = e.frame, frameSerial = it.frameSerial + 1, drifted = false, alignAttempt = 1) }
                logGeom("aligned")
                when (back) {
                    null -> startDemoOrReady(s.course, firstEntry = true)
                    Phase.COUNT_IN, Phase.FOLLOWING -> set { it.copy(phase = Phase.PAUSED) }
                    Phase.DEMO -> startDemoOrReady(s.course, firstEntry = false, forceReady = true)
                    else -> set { it.copy(phase = back) }
                }
            }
            JcEvent.AlignRetry -> if (s.phase == Phase.ALIGNING) set { it.copy(alignAttempt = it.alignAttempt + 1) }
            JcEvent.AlignFailed -> if (s.phase == Phase.ALIGNING) toBlocked(BlockReason.NO_HEAD_POSE)
            is JcEvent.Reframed -> {
                set { it.copy(frame = e.frame, frameSerial = it.frameSerial + 1, drifted = false) }
                logGeom("reframed")
            }
            is JcEvent.DriftChanged -> {
                if (e.drifted == s.drifted || s.frame == null) return
                if (e.drifted && (s.phase == Phase.COUNT_IN || s.phase == Phase.FOLLOWING)) pause()
                set { it.copy(drifted = e.drifted) }
            }

            // ---------------------------------------------------------------- the lesson clock
            is JcEvent.DemoFinished -> if (s.phase == Phase.DEMO && e.serial == s.demoSerial && s.demoPlaying) set { it.copy(demoPlaying = false) }
            is JcEvent.CountInDone -> if (s.phase == Phase.COUNT_IN && e.serial == s.runSerial) set { it.copy(phase = Phase.FOLLOWING) }
            is JcEvent.Counted -> if (s.phase == Phase.FOLLOWING && e.serial == s.runSerial && e.count != s.count) set { it.copy(count = e.count) }
            is JcEvent.SetComplete -> {
                if (s.phase != Phase.FOLLOWING || e.serial != s.runSerial) return
                val n = s.setSize.n ?: return
                store.setLastSet(s.course.number, n)
                set { it.copy(phase = Phase.SET_DONE, count = n, lastSets = it.lastSets + (s.course.number to n)) }
            }

            // ---------------------------------------------------------------- panel
            JcEvent.MainClicked -> primary("panel")
            is JcEvent.DwellFired -> dwellFired(e.target, e.source)
            JcEvent.PrevClicked -> toPicker(s.course.previous ?: s.course)
            JcEvent.NextClicked -> toPicker(s.course.next ?: s.course)
            JcEvent.WatchClicked -> if (s.phase == Phase.DEMO || s.phase == Phase.PAUSED || s.phase == Phase.SET_DONE) startDemo(s.course)
            JcEvent.MoreClicked -> {
                if (s.menuOpen) closeMenu()
                else if (s.phase != Phase.STARTING && s.phase != Phase.BLOCKED && s.phase != Phase.ALIGNING) openMenu()
            }
            JcEvent.TappedOutside -> if (s.menuOpen) closeMenu()
            is JcEvent.SpeedSelected -> setBody(s.body.copy(vt = e.speed.vt))
            is JcEvent.MuteSet -> setSettings(s.settings.copy(muted = e.muted))
            is JcEvent.BodyStep -> setBody(step(s.body, e.what, e.steps))
            is JcEvent.DemoSlowSelected -> setSettings(s.settings.copy(demoSlow = e.v))
            is JcEvent.SetSizeSelected -> setSettings(s.settings.copy(setSize = e.v))
            is JcEvent.DwellSelected -> setSettings(s.settings.copy(dwell = e.v))
            is JcEvent.AutoDemoSet -> setSettings(s.settings.copy(autoDemo = e.on))
            is JcEvent.LanguageSelected -> setSettings(s.settings.copy(language = e.v))
            JcEvent.RealignClicked -> {
                if (s.frame == null) return
                val back = if (s.phase == Phase.COUNT_IN || s.phase == Phase.FOLLOWING) Phase.PAUSED else s.phase
                set { it.copy(menuOpen = false, menuPage = MenuPage.MENU) }
                startAlign(back, fromFront = false)
            }
            JcEvent.ExitClicked -> emit(JcEffect.ExitApp)
            JcEvent.SpikeOpen -> if (s.menuOpen) set { it.copy(menuPage = MenuPage.SPIKE) }
            is JcEvent.SpikeCheck -> set { it.copy(spikeChecks = it.spikeChecks.toMutableList().also { l -> l[e.index] = e.on }) }
            JcEvent.SpikeSave -> {
                val b = s.body
                val c = s.spikeChecks
                val line =
                    String.format(
                        Locale.US,
                        "JC-SPIKE pass=%s peakInView=%s matches=%s beatOk=%s vt=%.1f peak=%.2f z=%.2f shoulder=%.2f handDrop=%.2f",
                        c.all { it }, c[0], c[1], c[2], b.vt, b.peakRise, b.zFront, b.shoulder, b.handDrop,
                    )
                JcLog.i(line)
                store.saveSpike(line)
                closeMenu()
            }

            // ---------------------------------------------------------------- lifecycle
            JcEvent.WentBackground -> {
                wasPractising = s.phase.takeIf { s.frame != null && (it.practising || it == Phase.ALIGNING) }
                if (s.phase == Phase.COUNT_IN || s.phase == Phase.FOLLOWING) pause()
                if (s.menuOpen) set { it.copy(menuOpen = false, menuPage = MenuPage.MENU) }
                if (s.phase == Phase.DEMO && s.demoPlaying) set { it.copy(demoPlaying = false) }
            }
            JcEvent.CameForeground -> {
                val was = wasPractising ?: return
                wasPractising = null
                val back = if (was == Phase.ALIGNING) (s.alignReturn ?: Phase.DEMO) else _state.value.phase
                startAlign(back, fromFront = false)
            }

            // ---------------------------------------------------------------- debug
            is JcEvent.DebugState -> debugState(e.id)
            is JcEvent.DebugCourse -> {
                set { it.copy(course = e.course, pickerCourse = e.course, count = 0, countBase = 0) }
                if (s.phase == Phase.DEMO) startDemoOrReady(e.course, firstEntry = false, forceReady = !s.demoPlaying)
            }
            is JcEvent.DebugVt -> setBody(s.body.copy(vt = e.vt))
            is JcEvent.DebugPhase -> set { it.copy(debugPhase = e.phase, debugHold = e.phase != null || it.debugHold) }
            is JcEvent.DebugDwell -> set { it.copy(debugDwell = e.seconds) }
            is JcEvent.DebugDrift -> {
                if (e.on && (s.phase == Phase.COUNT_IN || s.phase == Phase.FOLLOWING)) pause()
                set { it.copy(drifted = e.on, debugHold = e.on || it.debugHold) }
            }
            is JcEvent.DebugAnchor -> {
                set { it.copy(debugAnchor = e.frame) }
                if (e.frame != null) {
                    set { it.copy(frame = e.frame, frameSerial = it.frameSerial + 1, drifted = false) }
                    logGeom("anchor")
                }
            }
            is JcEvent.DebugBody -> setBody(e.body)
            is JcEvent.DebugBlocked -> if (e.on) toBlocked(BlockReason.NOT_IN_FULL_SPACE) else set { it.copy(phase = Phase.STARTING, blockReason = null) }
            is JcEvent.DebugSpike -> set { it.copy(spike = e.on) }
            is JcEvent.DebugPitch -> set { it.copy(debugPitchDeg = e.deg) }
            is JcEvent.DebugHeadFallback -> {
                if (e.on && !s.headFallback) flashHint(HintOverride.HEAD_FALLBACK, HEAD_HINT_MS)
                set { it.copy(headFallback = e.on) }
            }
            JcEvent.DebugRelease -> set { it.copy(debugHold = false, debugPhase = null, debugDwell = null, debugCountInBeat = null, debugAnchor = null, drifted = false, debugPitchDeg = null) }
            JcEvent.DebugLegendReset -> {
                store.legendSeen = emptySet()
                store.demoSeen = emptySet()
            }
            JcEvent.DebugClear -> {
                store.clear()
                set { JcUiState(phase = Phase.STARTING, spike = it.spike) }
            }
            is JcEvent.DebugTestTexture -> set { it.copy(testTexture = e.on) }
            else -> Unit
        }
    }

    // =========================================================================================
    // transitions
    // =========================================================================================

    private fun chooseCourse(course: Course) {
        val s = _state.value
        if (s.spike && course != Course.P1) return
        set { it.copy(course = course, pickerCourse = course, count = 0, countBase = 0, firstPicker = false) }
        val f = s.frame
        if (f != null && !driftedFrom(f)) {
            startDemoOrReady(course, firstEntry = true)
        } else {
            startAlign(null, fromFront = true)
        }
    }

    /** True when the head is more than 0.30 m / 25 degrees away from [f] (design S02 exit rule). */
    private fun driftedFrom(f: BodyFrame): Boolean {
        val p = headPos ?: return false
        val d = Vec3(p.x - f.origin.x, 0f, p.z - f.origin.z).length
        return d > DRIFT_M || abs(angle(headYaw - f.yaw)) > DRIFT_RAD
    }

    private fun startAlign(back: Phase?, fromFront: Boolean) {
        set { it.copy(phase = Phase.ALIGNING, alignReturn = back, alignFromFront = fromFront, alignAttempt = 1, alignSerial = it.alignSerial + 1, menuOpen = false, demoPlaying = false, drifted = false) }
    }

    /** S04 for [course]: the demo plays by itself the first time (auto demo on), otherwise the ready pose. */
    private fun startDemoOrReady(course: Course, firstEntry: Boolean, forceReady: Boolean = false) {
        val seen = store.demoSeen
        val auto = _state.value.settings.autoDemo && course.number !in seen
        if (!forceReady && firstEntry && auto) startDemo(course)
        else set { it.copy(phase = Phase.DEMO, course = course, demoPlaying = false, count = 0, countBase = 0, menuOpen = false) }
    }

    private fun startDemo(course: Course) {
        store.demoSeen = store.demoSeen + course.number
        set { it.copy(phase = Phase.DEMO, course = course, demoPlaying = true, demoSerial = it.demoSerial + 1, count = 0, countBase = 0, menuOpen = false) }
        val legendSeen = store.legendSeen
        if (course.number !in legendSeen) {
            store.legendSeen = legendSeen + course.number
            viewModelScope.launch {
                flash(HintOverride.LEGEND, LEGEND_MS)
                if (course == Course.P4 || course == Course.P6) flash(HintOverride.THREE_SCARVES, LEGEND_MS)
            }
        }
    }

    private suspend fun flash(h: HintOverride, ms: Long) {
        set { it.copy(hintOverride = h) }
        delay(ms)
        set { if (it.hintOverride == h) it.copy(hintOverride = null) else it }
    }

    private fun flashHint(h: HintOverride, ms: Long) {
        hintJob?.cancel()
        hintJob = viewModelScope.launch { flash(h, ms) }
    }

    /** Start / skip-and-start / resume / one more set (count-in first, design I5 / I6 / I7). */
    private fun startRun(keepCount: Boolean) {
        set {
            val base = if (keepCount) it.count else 0
            it.copy(phase = Phase.COUNT_IN, runSerial = it.runSerial + 1, countBase = base, count = base, demoPlaying = false, menuOpen = false, debugPhase = null)
        }
    }

    private fun pause() {
        set { it.copy(phase = Phase.PAUSED) }
    }

    private fun primary(source: String) {
        val s = _state.value
        JcLog.i("BUTTON main source=$source phase=${s.phase} drifted=${s.drifted}")
        if (s.drifted && s.frame != null && s.phase.practising) {
            startAlign(if (s.phase == Phase.COUNT_IN || s.phase == Phase.FOLLOWING) Phase.PAUSED else s.phase, fromFront = false)
            return
        }
        when (s.phase) {
            Phase.DEMO -> startRun(keepCount = false)
            Phase.COUNT_IN, Phase.FOLLOWING -> pause()
            Phase.PAUSED -> startRun(keepCount = true)
            Phase.SET_DONE -> startRun(keepCount = false)
            else -> Unit
        }
    }

    private fun dwellFired(target: String, source: String) {
        val s = _state.value
        JcLog.i("DWELL fired target=$target source=$source phase=${s.phase}")
        when (target) {
            DwellTarget.WELCOME -> handle(JcEvent.WelcomeGo)
            DwellTarget.LEFT_DISC -> primary(source)
            DwellTarget.RIGHT_DISC -> {
                val next = s.course.next ?: return
                if (s.spike) return
                if (s.phase == Phase.DEMO || s.phase == Phase.PAUSED || s.phase == Phase.SET_DONE) startDemoOrReadyNext(next)
            }
            else -> DwellTarget.courseOf(target)?.let { chooseCourse(it) }
        }
    }

    /** Right disc: straight to the next lesson's demo (always plays when auto demo is on, design I10). */
    private fun startDemoOrReadyNext(next: Course) {
        set { it.copy(course = next, pickerCourse = next, count = 0, countBase = 0) }
        if (_state.value.settings.autoDemo) startDemo(next) else set { it.copy(phase = Phase.DEMO, demoPlaying = false) }
    }

    private fun toPicker(preselect: Course) {
        val s = _state.value
        if (s.phase != Phase.DEMO && s.phase != Phase.PAUSED && s.phase != Phase.SET_DONE) return
        set { it.copy(phase = Phase.PICKER, pickerCourse = preselect, demoPlaying = false, menuOpen = false, drifted = false) }
    }

    private fun openMenu() {
        val s = _state.value
        if (s.phase == Phase.COUNT_IN || s.phase == Phase.FOLLOWING) pause()
        set { it.copy(menuOpen = true, menuPage = MenuPage.MENU, demoPlaying = if (it.phase == Phase.DEMO) false else it.demoPlaying) }
    }

    private fun closeMenu() = set { it.copy(menuOpen = false, menuPage = MenuPage.MENU) }

    private fun toBlocked(reason: BlockReason) {
        JcLog.i("BLOCKED reason=$reason")
        set { it.copy(phase = Phase.BLOCKED, blockReason = reason, menuOpen = false, demoPlaying = false, drifted = false) }
    }

    private fun setSettings(settings: Settings) {
        store.save(settings)
        set { it.copy(settings = settings) }
    }

    private fun setBody(body: BodyParams) {
        val b = body.clamped()
        if (b == _state.value.body) return
        setSettings(_state.value.settings.copy(body = b))
        logGeom("params")
    }

    private fun step(b: BodyParams, what: BodyField, n: Int): BodyParams {
        fun q(v: Float, step: Float) = Math.round(v / step) * step
        return when (what) {
            BodyField.SHOULDER -> b.copy(shoulder = q(b.shoulder + n * BodyParams.SHOULDER_STEP, 0.01f))
            BodyField.HAND_DROP -> b.copy(handDrop = q(b.handDrop + n * BodyParams.HAND_DROP_STEP, 0.01f))
            BodyField.PEAK -> b.copy(peakRise = q(b.peakRise + n * BodyParams.PEAK_STEP, 0.01f))
            BodyField.Z_FRONT -> b.copy(zFront = q(b.zFront + n * BodyParams.Z_FRONT_STEP, 0.01f))
            BodyField.VT -> b.copy(vt = q(b.vt + n * BodyParams.VT_STEP, 0.1f))
        }
    }

    // =========================================================================================
    // debug states (design `qaHooks`: a jump holds the state against real sensing until released)
    // =========================================================================================

    private fun debugState(id: String) {
        val s = _state.value
        val frame = s.frame ?: s.debugAnchor ?: headPos?.let { BodyFrame(it, headYaw) }
        fun withFrame(p: Phase, extra: (JcUiState) -> JcUiState = { it }) =
            set {
                extra(
                    it.copy(
                        phase = p,
                        frame = it.frame ?: frame,
                        frameSerial = if (it.frame == null) it.frameSerial + 1 else it.frameSerial,
                        menuOpen = false,
                        menuPage = MenuPage.MENU,
                        drifted = false,
                        debugHold = true,
                        debugPhase = null,
                        debugCountInBeat = null,
                        blockReason = null,
                    )
                )
            }
        when (id.uppercase(Locale.US)) {
            "S00" -> set { it.copy(phase = Phase.STARTING, debugHold = true, menuOpen = false) }
            "S01" -> set { it.copy(phase = Phase.WELCOME, debugHold = true, menuOpen = false) }
            "S02" -> set { it.copy(phase = Phase.PICKER, debugHold = true, menuOpen = false, drifted = false) }
            "S03" -> set { it.copy(phase = Phase.ALIGNING, alignReturn = null, alignFromFront = true, alignAttempt = 1, alignSerial = it.alignSerial + 1, debugHold = true, menuOpen = false) }
            "S04" -> withFrame(Phase.DEMO) { it.copy(demoPlaying = true, demoSerial = it.demoSerial + 1) }
            "S04R" -> withFrame(Phase.DEMO) { it.copy(demoPlaying = false) }
            "S05A" -> withFrame(Phase.COUNT_IN) { it.copy(runSerial = it.runSerial + 1, countBase = 0, count = 0, debugCountInBeat = 1) }
            "S05" -> withFrame(Phase.FOLLOWING) { it.copy(runSerial = it.runSerial + 1, countBase = 0, count = 3, debugPhase = 0.5f) }
            "S06" -> withFrame(Phase.PAUSED) { it.copy(count = 3, debugPhase = 0.5f) }
            "S07" -> withFrame(Phase.SET_DONE) { it.copy(count = it.setSize.n ?: 10) }
            "S09" -> withFrame(if (s.phase.practising) s.phase else Phase.DEMO) { it.copy(menuOpen = true, menuPage = MenuPage.MENU, demoPlaying = false) }
            "S10" -> withFrame(Phase.PAUSED) { it.copy(drifted = true, count = 3) }
            "S11" -> {
                toBlocked(BlockReason.NOT_IN_FULL_SPACE)
                set { it.copy(debugHold = true) }
            }
            "K1" -> withFrame(Phase.FOLLOWING) { it.copy(spike = true, course = Course.P1, runSerial = it.runSerial + 1, count = 3, debugPhase = 0.5f) }
            "K2" -> withFrame(if (s.phase.practising) s.phase else Phase.DEMO) { it.copy(menuOpen = true, menuPage = MenuPage.SPIKE, demoPlaying = false) }
            else -> JcLog.i("CMD state $id unknown")
        }
        logGeom("debug-state")
    }

    // =========================================================================================
    // plumbing
    // =========================================================================================

    private inline fun set(f: (JcUiState) -> JcUiState) {
        _state.update(f)
    }

    private fun emit(effect: JcEffect) {
        _effects.tryEmit(effect)
    }

    private fun logState() {
        val s = _state.value
        val line =
            String.format(
                Locale.US,
                "JC-STATE %s course=%d vt=%.1f phase=%s n=%d%s",
                s.qaName,
                s.course.number,
                s.body.vt,
                s.debugPhase?.let { String.format(Locale.US, "%.2f", it) } ?: (if (s.demoPlaying) "demo" else "-"),
                s.count,
                if (s.debugHold) " hold" else "",
            )
        if (line != lastLogged) {
            lastLogged = line
            JcLog.i(line)
        }
    }

    private fun logGeom(why: String) {
        val s = _state.value
        val f = s.frame ?: return
        val b = s.body
        JcLog.i(
            String.format(
                Locale.US,
                "JC-GEOM why=%s course=%d origin=%s yaw=%.1f shoulder=%.2f handDrop=%.2f zFront=%.2f peak=%.2f vt=%.1f",
                why, s.course.number, JcLog.pos(f.origin), Math.toDegrees(f.yaw.toDouble()), b.shoulder, b.handDrop, b.zFront, b.peakRise, b.vt,
            )
        )
    }

    fun dumpJson(): String {
        val s = _state.value
        return buildString {
            append("{")
            append("\"state\":\"${s.qaName}\",\"phase\":\"${s.phase.name}\",\"course\":${s.course.number},\"count\":${s.count},\"countBase\":${s.countBase},")
            append("\"demoPlaying\":${s.demoPlaying},\"menu\":${s.menuOpen},\"menuPage\":\"${s.menuPage}\",\"drifted\":${s.drifted},\"headFallback\":${s.headFallback},")
            append("\"frame\":${s.frame?.let { "{\"origin\":\"${JcLog.pos(it.origin)}\",\"yawDeg\":${JcLog.f(Math.toDegrees(it.yaw.toDouble()).toFloat(), 1)}}" } ?: "null"},")
            append("\"dwell\":\"${s.dwellTarget}\",\"dwellProgress\":${s.dwellProgress},\"spike\":${s.spike},\"settings\":\"${s.settings}\",\"head\":\"${JcLog.pos(headPos)}\"")
            append("}")
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = JcViewModel(JcGraph.settings(context.applicationContext)) as T
    }

    companion object {
        const val DRIFT_M = 0.30f
        val DRIFT_RAD = Math.toRadians(25.0).toFloat()
        private const val LEGEND_MS = 3_000L
        private const val HEAD_HINT_MS = 5_000L

        fun angle(a: Float): Float {
            var d = a
            while (d > PI) d -= (2 * PI).toFloat()
            while (d < -PI) d += (2 * PI).toFloat()
            return d
        }
    }
}
