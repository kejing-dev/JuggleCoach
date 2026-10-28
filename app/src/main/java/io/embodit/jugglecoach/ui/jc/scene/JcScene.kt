package io.embodit.jugglecoach.ui.jc.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.jugglecoach.domain.juggle.BodyFrame
import io.embodit.jugglecoach.domain.juggle.Course
import io.embodit.jugglecoach.domain.juggle.Lesson
import io.embodit.jugglecoach.domain.juggle.P2
import io.embodit.jugglecoach.domain.model.BodyParams
import io.embodit.jugglecoach.domain.model.Hand
import io.embodit.jugglecoach.domain.model.Quat4
import io.embodit.jugglecoach.domain.model.Vec3
import io.embodit.jugglecoach.platform.JcGraph
import io.embodit.jugglecoach.platform.JcLog
import io.embodit.jugglecoach.platform.audio.BeatEngine
import io.embodit.jugglecoach.platform.tracking.HeadPose
import io.embodit.jugglecoach.ui.jc.DwellTarget
import io.embodit.jugglecoach.ui.jc.JcEvent
import io.embodit.jugglecoach.ui.jc.JcUiState
import io.embodit.jugglecoach.ui.jc.JcViewModel
import io.embodit.jugglecoach.ui.jc.Phase
import io.embodit.jugglecoach.ui.jc.debug.JcProbe
import io.embodit.jugglecoach.ui.theme.JcColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: JcScene? = null
}

/** The one per-frame ECS system of the app: alignment, the lesson clock, drift, dwell and panel follow run here. */
class JcFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the body-frame content (two paths, peak rings, hand discs, ghost scarves, count-in digit),
 * the two dwell discs, the gaze dot, the alignment reticle, and the placement of the pre-built panels. Compose state
 * arrives through [render]; everything that moves (scarves by the audio clock, cues, fades, the 0.3 s re-layout, the
 * 0.5 s slide to a new frame, the panel's lazy follow, gaze hits) is written in [onFrame] and never goes through
 * Compose. The scene reports milestones only (aligned, count-in done, counted, set done, drift, dwell). Everything
 * started in [attach] is stopped in [detach]. Main thread only.
 */
class JcScene(private val sink: (JcEvent) -> Unit) : JcProbe.SceneProbe {

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val headTracker = JcGraph.head
    private val eyeTracker = JcGraph.eye
    private val audio = JcGraph.audio

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var shared: SharedResources? = null
    private var materials: JcMaterials? = null
    private var meshes: JcMeshes? = null
    private var rig: PanelRig? = null
    private var body: Entity? = null
    private var paths = HashMap<Hand, PathView>()
    private var rings = HashMap<Hand, PeakRingView>()
    private var handDiscs = HashMap<Hand, HandDiscView>()
    private var scarves = ArrayList<ScarfView>()
    private var discLeft: DwellDiscView? = null
    private var discRight: DwellDiscView? = null
    private var gazeDot: GazeDotView? = null
    private var reticle: ReticleView? = null
    private var digit: DigitView? = null
    private var light: Entity? = null
    private var backstop: Entity? = null
    private var attached = false
    private var sensing = false
    private var panelsReported = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = JcUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var firstHeadMs = NEVER
    private var lastHeadSentMs = 0L
    private var lastFrameMs = 0L
    private var lastFrameDtMs = 0L
    private var blockSinceMs = NEVER
    private var blockSent = false
    private val eyeSamples = ArrayList<Float>()
    private var eyeHeight = Float.NaN

    // lesson clock
    private var armedDemo = -1
    private var armedRun = -1
    private var frozenT = REST
    private var lastT = REST
    private var milestoneDemo = -1
    private var milestoneCountIn = -1
    private var milestoneSet = -1
    private var lastCount = -1
    private var phaseSeen: Phase? = null
    private var sentBeat: Int? = null

    // shown geometry: params ease over 0.3 s, frame slides over 0.5 s, content fades in over 0.5 s
    private var shownParams = BodyParams()
    private var paramsFrom = BodyParams()
    private var paramsTarget = BodyParams()
    private var paramsStartMs = NEVER
    private var shownFrame: BodyFrame? = null
    private var frameFrom: BodyFrame? = null
    private var frameTo: BodyFrame? = null
    private var frameStartMs = NEVER
    private var frameSerialSeen = -1
    private var fadeStartMs = NEVER
    private var lesson: Lesson? = null
    private var lessonKey: Pair<Course, BodyParams>? = null

    // alignment
    private var alignSerialSeen = -1
    private var alignStartMs = NEVER
    private var alignFrames = 0
    private val alignPos = ArrayList<Vec3>()
    private val alignYaw = ArrayList<Float>()

    // drift
    private var driftSinceMs = NEVER
    private var stillSinceMs = NEVER
    private var backSinceMs = NEVER
    private var prevHead: Vec3? = null
    private var prevYaw = 0f
    private var driftSent = false

    // gaze + dwell
    private var dwellTarget: String? = null
    private var dwellStartMs = NEVER
    private var dwellCoolUntil = 0L
    private var dwellBlocked: String? = null
    private var dwellKey = ""
    private var lastGazeHit: String? = null
    private var sentTarget: String? = null
    private var sentProgress = -1f
    private var eyeBadSinceMs = NEVER
    private var eyeGoodSinceMs = NEVER
    private var usingHeadNow = false
    private var lastEyePollMs = 0L
    private var sentEye: Pair<Boolean, Boolean>? = null

    // panel follow (design `rules.panelFollow`)
    private var anchorYaw = Float.NaN
    private var anchorHead = Vec3.ZERO
    private var outSinceMs = NEVER
    private var slideFromYaw = 0f
    private var slideFromHead = Vec3.ZERO
    private var slideStartMs = NEVER
    private var frontMode: Boolean? = null
    private var modeFrom: Pair<Vec3, Float>? = null
    private var modeStartMs = NEVER
    private var lastPanelPos: Vec3? = null
    private var lastPanelYaw = 0f
    private var dialogPlaced = false
    private var shownPanel: String? = null

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        // An entity added to the SpatialView is not at the Stage origin: move the root once so that its local space
        // equals Stage space (Spatial Ruler).
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        JcLog.i("STAGE root offset=${JcLog.pos(offset.domain())}")
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h).also { if (id != PanelSpec.DIALOG) it.autoFace = false }
        }
        buildBackstop(root)
        startSensing()
        ActiveScene.scene = this
        bindPanels(attachments)
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) JcLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /** Head and eye tracking and the audio stream (no planes, no anchors, no hand tracking: design `lifecycle`). */
    private fun startSensing() {
        if (sensing) return
        sensing = true
        headTracker.start()
        eyeTracker.start()
        audio.start()
    }

    private fun stopSensing() {
        if (!sensing) return
        sensing = false
        headTracker.stop()
        eyeTracker.stop()
        audio.stop()
        hasHead = false
        armedDemo = -1
        armedRun = -1
    }

    fun onBackground() {
        stopSensing()
        JcLog.i("SENSING stopped (background)")
    }

    fun onForeground() {
        if (stageRoot == null) return
        startSensing()
        JcLog.i("SENSING started (foreground)")
    }

    /** Restarts the eye tracker after the permission dialog (its support state then changes). */
    fun restartEyes() {
        eyeTracker.stop()
        eyeTracker.start()
    }

    /**
     * Materials, meshes and textures, a few at a time: each mesh / texture waits for the render thread, and at the idle
     * emulator's 1 - 2 fps a batch of them blocks the main thread into an ANR (factory finding). Nothing of this is
     * needed before S03, so the panels and S01 / S02 work meanwhile.
     */
    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = JcMaterials(sh)
        materials = m
        yield()
        val ms = JcMeshes()
        ms.build(sh) { pace(MESH_GAP_SLOW_MS) }
        meshes = ms
        JcLog.i("SCENE meshes built ms=${SystemClock.elapsedRealtime() - t0}")
        m.buildTextures { pace(TEXTURE_GAP_SLOW_MS) }
        JcLog.i("SCENE textures built ms=${SystemClock.elapsedRealtime() - t0} hands=${m.handBase.size} icons=${m.icons.size} digits=${m.digits.size}")
        pace(ENTITY_GAP_SLOW_MS)
        val b = Entity().named("bodyContent")
        root.addChild(b)
        body = b
        for (h in Hand.entries) {
            paths[h] = PathView(b, ms, m, h)
            rings[h] = PeakRingView(b, ms, m, "peakRing_$h")
        }
        pace(ENTITY_GAP_SLOW_MS)
        for (h in Hand.entries) handDiscs[h] = HandDiscView(b, ms, m, h)
        for (i in 0 until 3) scarves += ScarfView(b, ms, m, i)
        digit = DigitView(b, ms, m)
        pace(ENTITY_GAP_SLOW_MS)
        discLeft = DwellDiscView(root, ms, m, DwellTarget.LEFT_DISC)
        discRight = DwellDiscView(root, ms, m, DwellTarget.RIGHT_DISC)
        gazeDot = GazeDotView(root, ms, m)
        reticle = ReticleView(root, ms, m)
        // one directional light from above and ahead of the user (design `spatialLayout.stage`); the content is unlit
        light = Entity().named("keyLight").also {
            it.components.set(DirectionalLightComponent(JcColors.ArcWhite.color4(), 3000f, false))
            it.setLocal(rotation = Quat4.fromTo(Vec3.AHEAD, Vec3(0f, -0.7f, -0.7f).normalized()))
            root.addChild(it)
        }
        attached = true
        JcLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
    }

    /**
     * Gives the main thread back between expensive steps: one frame when frames are fast (headset), [slowMs] when the
     * engine runs at the idle emulator's 1 - 2 fps (or has not drawn a frame yet).
     */
    private suspend fun pace(slowMs: Long) {
        val fast = lastFrameDtMs in 1..FAST_FRAME_MS
        delay(if (fast) 16L else slowMs)
    }

    /** A shell of six slabs 6 m around the start pose: a tap into the room lands on it ("tap outside closes the menu"). */
    private fun buildBackstop(root: Entity) {
        val shell = Entity().named(BACKSTOP)
        root.addChild(shell)
        val h = 6f
        val t = 0.2f
        val slabs =
            listOf(
                Vec3(h, 1.5f, 0f) to Vec3(t, 2 * h, 2 * h),
                Vec3(-h, 1.5f, 0f) to Vec3(t, 2 * h, 2 * h),
                Vec3(0f, 1.5f, h) to Vec3(2 * h, 2 * h, t),
                Vec3(0f, 1.5f, -h) to Vec3(2 * h, 2 * h, t),
                Vec3(0f, 1.5f + h, 0f) to Vec3(2 * h, t, 2 * h),
                Vec3(0f, 1.5f - h, 0f) to Vec3(2 * h, t, 2 * h),
            )
        for ((center, size) in slabs) {
            val slab = Entity().named(BACKSTOP)
            slab.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(size.sdk())), physicsMaterial = PhysicsMaterialResource()))
            slab.components.set(InteractableComponent())
            shell.addChild(slab)
            slab.setLocal(position = center)
        }
        backstop = shell
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        stopSensing()
        panelShown.clear()
        runCatching { rig?.release() }
        paths.values.forEach { it.destroy() }
        rings.values.forEach { it.destroy() }
        handDiscs.values.forEach { it.destroy() }
        scarves.forEach { it.destroy() }
        discLeft?.destroy()
        discRight?.destroy()
        gazeDot?.destroy()
        reticle?.destroy()
        digit?.destroy()
        runCatching { light?.destroy(true) }
        runCatching { backstop?.destroy(true) }
        runCatching { body?.destroy(true) }
        runCatching { stageRoot?.destroy(true) }
        shared?.close()
        shared = null
        paths.clear()
        rings.clear()
        handDiscs.clear()
        scarves.clear()
        discLeft = null
        discRight = null
        gazeDot = null
        reticle = null
        digit = null
        light = null
        backstop = null
        body = null
        stageRoot = null
        content = null
        materials = null
        meshes = null
        rig = null
        dialogPlaced = false
        panelsReported = false
        anchorYaw = Float.NaN
        JcLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> scene (on state change)
    // =========================================================================================

    fun render(state: JcUiState) {
        val previous = ui
        ui = state
        val now = SystemClock.elapsedRealtime()
        audio.muted = state.settings.muted
        if (state.body != paramsTarget) {
            paramsFrom = shownParams
            paramsTarget = state.body
            paramsStartMs = if (frameSerialSeen < 0) NEVER else now
            if (paramsStartMs == NEVER) shownParams = state.body
        }
        if (state.frameSerial != frameSerialSeen) {
            val f = state.frame
            if (f != null) {
                val from = shownFrame
                if (from == null || previous.phase == Phase.ALIGNING || state.phase == Phase.ALIGNING) {
                    shownFrame = f
                    frameStartMs = NEVER
                } else {
                    frameFrom = from
                    frameTo = f
                    frameStartMs = now
                }
            }
            frameSerialSeen = state.frameSerial
        }
        if (state.phase != phaseSeen) {
            val was = phaseSeen
            phaseSeen = state.phase
            if (state.phase == Phase.PAUSED && (was == Phase.FOLLOWING || was == Phase.COUNT_IN)) frozenT = lastT
            if (state.phase.practising && (was == Phase.PICKER || was == Phase.ALIGNING || was == null || was == Phase.STARTING)) fadeStartMs = now
        }
        armAudio(state)
        syncPanels(state)
    }

    /** The lesson clock: a demo run or a count-in + set run on the audio stream; anything else is silence. */
    private fun armAudio(s: JcUiState) {
        val demo = s.phase == Phase.DEMO && s.demoPlaying && s.debugPhase == null
        val run = (s.phase == Phase.COUNT_IN || s.phase == Phase.FOLLOWING) && s.debugPhase == null && s.debugCountInBeat == null
        if (demo && s.demoSerial != armedDemo) {
            armedDemo = s.demoSerial
            val l = Lesson(s.course, s.body)
            logSched(l, "demo")
            val end = l.unitDoneAt(l.demoUnits) + 0.01f
            audio.arm(BeatEngine.Run(l, startAt = -l.carry - DEMO_LEAD_S, speed = s.settings.demoSlow.k, countIn = false, endAt = end))
            milestoneDemo = -1
        } else if (run && s.runSerial != armedRun) {
            armedRun = s.runSerial
            val l = Lesson(s.course, s.body)
            logSched(l, "run")
            val left = s.setSize.n?.let { it - s.countBase }
            val end = if (left == null) Float.MAX_VALUE else l.unitDoneAt(left) + 0.01f
            audio.arm(BeatEngine.Run(l, startAt = -Lesson.COUNT_IN_TOTAL, speed = 1f, countIn = true, endAt = end))
            milestoneCountIn = -1
            milestoneSet = -1
            lastCount = -1
        }
        if (!demo && !run) {
            if (audio.armed) audio.silence()
            if (!(s.phase == Phase.DEMO && s.demoPlaying)) armedDemo = -1
            if (s.phase != Phase.COUNT_IN && s.phase != Phase.FOLLOWING) armedRun = -1
        }
    }

    private fun logSched(l: Lesson, why: String) {
        JcLog.i(
            String.format(
                Locale.US,
                "JC-SCHED why=%s course=%d F=%.3f up=%.3f carry=%.3f I=%.3f period=%.3f demoUnits=%d vt=%.2f peak=%.2f",
                why, l.course.number, l.flight, l.paths.getValue(Hand.RIGHT).up, l.carry, l.interval, l.unitPeriod, l.demoUnits, l.params.vt, l.params.peakRise,
            )
        )
    }

    private fun syncPanels(state: JcUiState) {
        val rig = rig ?: return
        val dialog = rig[PanelSpec.DIALOG]
        if (state.phase == Phase.BLOCKED) {
            if (!dialogPlaced && hasHead) {
                val f = flatForward()
                val down = Math.tan(Math.toRadians(5.0)).toFloat() * DIALOG_DISTANCE_M
                dialog?.show(head.position + f * DIALOG_DISTANCE_M - Vec3(0f, down, 0f), PanelSpec.SCALE)
                dialogPlaced = true
            }
        } else {
            dialog?.hide()
            dialogPlaced = false
        }
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        if (lastFrameMs != 0L) lastFrameDtMs = now - lastFrameMs
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.2f)
        lastFrameMs = now
        readHead(now)
        val rig = rig ?: return
        rig.onFrame(now)
        if (hasHead) rig.stowBehind(head.view)
        placePanels(rig, now)
        // the blocked dialog (the only auto-facing slot) turns to the viewer every frame
        if (hasHead) rig.faceViewer(head.position)
        reportPanels(rig)
        val state = ui
        if (state.phase == Phase.BLOCKED && !dialogPlaced) syncPanels(state)
        pollBlock(now)
        pollEyes(now)
        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            sink(JcEvent.HeadSample(head.position, headYaw()))
        }
        if (!attached) return
        easeGeometry(now)
        alignFrame(state, now)
        val t = lessonTime(state)
        lastT = t
        milestones(state, t)
        contentFrame(state, now, t)
        driftFrame(state, now, dt)
        gazeFrame(state, now)
    }

    private fun reportPanels(rig: PanelRig) {
        if (panelsReported) return
        val ready = PanelSpec.pool.all { rig[it]?.panel != null } && hasHead
        if (ready) {
            panelsReported = true
            sink(JcEvent.PanelsReady)
        }
    }

    /** Head pose; the first samples of a fresh tracker are the origin and are dropped. Eye height = median of the first second. */
    private fun readHead(now: Long) {
        val p = headTracker.pose() ?: return
        if (p.position.length < 1e-4f) return
        head = p
        if (!hasHead) {
            hasHead = true
            firstHeadMs = now
        }
        if (eyeHeight.isNaN()) {
            eyeSamples += p.position.y
            if (now - firstHeadMs >= EYE_SAMPLE_MS) {
                eyeHeight = eyeSamples.sorted()[eyeSamples.size / 2]
                eyeSamples.clear()
                JcLog.i("PANEL eye_height=${JcLog.f(eyeHeight, 3)}")
            }
        }
    }

    private fun pollBlock(now: Long) {
        val reason = headTracker.blockReason()
        if (reason == null || ui.phase == Phase.BLOCKED || ui.debugHold) {
            blockSinceMs = NEVER
            if (ui.phase != Phase.BLOCKED) blockSent = false
            return
        }
        if (blockSinceMs == NEVER) blockSinceMs = now
        if (!blockSent && now - blockSinceMs >= BLOCK_AFTER_MS) {
            blockSent = true
            sink(JcEvent.BlockChanged(reason))
        }
    }

    /** Eye availability twice a second: refused / unsupported = S13; a valid source without a fresh sample = amber. */
    private fun pollEyes(now: Long) {
        if (now - lastEyePollMs < EYE_POLL_MS) return
        lastEyePollMs = now
        val reason = eyeTracker.unavailableReason(now)
        val permanent = reason == "permission" || reason == "unsupported"
        val state = permanent to (usingHeadNow && !permanent)
        if (state != sentEye) {
            sentEye = state
            JcLog.i("EYE reason=$reason permanent=$permanent amber=${state.second}")
            sink(JcEvent.EyeState(permanent, state.second))
        }
    }

    private fun easeGeometry(now: Long) {
        if (paramsStartMs != NEVER) {
            val q = ((now - paramsStartMs) / PARAMS_EASE_MS).coerceIn(0f, 1f)
            shownParams = paramsFrom.lerp(paramsTarget, Ease.inOutCubic(q))
            if (q >= 1f) {
                paramsStartMs = NEVER
                shownParams = paramsTarget
                logGeom()
            }
        }
        if (frameStartMs != NEVER) {
            val a = frameFrom
            val b = frameTo
            if (a == null || b == null) {
                frameStartMs = NEVER
            } else {
                val q = ((now - frameStartMs) / FRAME_SLIDE_MS).coerceIn(0f, 1f)
                val e = Ease.inOutCubic(q)
                shownFrame = BodyFrame(a.origin.lerp(b.origin, e), a.yaw + JcViewModel.angle(b.yaw - a.yaw) * e)
                if (q >= 1f) {
                    shownFrame = b
                    frameStartMs = NEVER
                }
            }
        }
    }

    /** The lesson drawn now (rebuilt when the lesson or the shown parameters change). */
    private fun lessonNow(course: Course): Lesson {
        val key = course to shownParams
        val l = lesson
        if (l != null && lessonKey == key) return l
        return Lesson(course, shownParams).also {
            lesson = it
            lessonKey = key
        }
    }

    // ---- S03 ------------------------------------------------------------------------------

    /**
     * Alignment (design `rules.bodyFrame.align`): 1.0 s of head poses -> the median position and yaw = body frame F.
     * Fewer than half the frames valid -> another second (at most 3 tries) -> S11. A debug hold stops half way.
     */
    private fun alignFrame(state: JcUiState, now: Long) {
        val r = reticle ?: return
        if (state.phase != Phase.ALIGNING) {
            alignSerialSeen = -1
            r.hide()
            return
        }
        if (state.alignSerial != alignSerialSeen) {
            alignSerialSeen = state.alignSerial
            alignStartMs = now
            alignFrames = 0
            alignPos.clear()
            alignYaw.clear()
        }
        alignFrames++
        if (hasHead) {
            alignPos += head.position
            alignYaw += headYaw()
        }
        var q = ((now - alignStartMs) / ALIGN_MS).coerceIn(0f, 1f)
        if (state.debugHold) q = q.coerceAtMost(0.5f)
        // the reticle: 0.40 m ahead at eye height, from the current head (it shows where F will be)
        val f = BodyFrame(head.position, headYaw())
        r.show(f.toStage(0f, 0f, state.body.zFront), f.rotation, q)
        if (q < 1f || state.debugHold) return
        val valid = alignPos.size
        if (valid == 0 || valid * 2 < alignFrames) {
            if (state.alignAttempt >= ALIGN_TRIES) sink(JcEvent.AlignFailed) else {
                alignStartMs = now
                alignFrames = 0
                sink(JcEvent.AlignRetry)
            }
            return
        }
        val xs = alignPos.map { it.x }.sorted()
        val ys = alignPos.map { it.y }.sorted()
        val zs = alignPos.map { it.z }.sorted()
        val mid = valid / 2
        val origin = Vec3(xs[mid], ys[mid], zs[mid])
        val yaw = alignYaw.sorted()[mid]
        val frame = state.debugAnchor ?: BodyFrame(origin, yaw)
        JcLog.i("ALIGN done samples=$valid/$alignFrames origin=${JcLog.pos(frame.origin)} yaw=${JcLog.f(Math.toDegrees(frame.yaw.toDouble()).toFloat(), 1)}")
        alignSerialSeen = -2
        r.hide()
        fadeStartMs = now
        sink(JcEvent.Aligned(frame))
    }

    // ---- the lesson clock -----------------------------------------------------------------

    /**
     * Lesson time now (0 = the first release; the count-in runs from -3.2 s). Debug phase / count-in holds freeze it;
     * a pause freezes it where it was; the ready pose, the set-done pose and the open menu rest every scarf in its hand.
     */
    private fun lessonTime(s: JcUiState): Float {
        val l = lessonNow(s.course)
        s.debugPhase?.let { return l.phaseStart + it * l.phaseLength }
        if (s.phase == Phase.COUNT_IN && s.debugCountInBeat != null) return -Lesson.COUNT_IN_TOTAL + s.debugCountInBeat * Lesson.COUNT_IN_S + 0.15f
        if (s.menuOpen) return REST
        return when (s.phase) {
            Phase.DEMO -> if (s.demoPlaying) audio.lessonTime() ?: REST else REST
            Phase.COUNT_IN, Phase.FOLLOWING -> audio.lessonTime() ?: -Lesson.COUNT_IN_TOTAL
            Phase.PAUSED -> frozenT
            else -> REST
        }
    }

    private fun milestones(s: JcUiState, t: Float) {
        if (s.debugPhase != null || s.debugCountInBeat != null || s.debugHold && s.phase != Phase.DEMO) return
        val l = lessonNow(s.course)
        when (s.phase) {
            Phase.DEMO ->
                if (s.demoPlaying && armedDemo == s.demoSerial && milestoneDemo != s.demoSerial && t >= l.unitDoneAt(l.demoUnits) + DEMO_TAIL_S) {
                    milestoneDemo = s.demoSerial
                    sink(JcEvent.DemoFinished(s.demoSerial))
                }
            Phase.COUNT_IN ->
                if (armedRun == s.runSerial && milestoneCountIn != s.runSerial && t >= 0f) {
                    milestoneCountIn = s.runSerial
                    sink(JcEvent.CountInDone(s.runSerial))
                }
            Phase.FOLLOWING -> {
                if (armedRun != s.runSerial) return
                val n = s.countBase + l.countAt(t)
                if (n != lastCount) {
                    lastCount = n
                    sink(JcEvent.Counted(s.runSerial, n))
                }
                val total = s.setSize.n ?: return
                val left = total - s.countBase
                if (milestoneSet != s.runSerial && left > 0 && t >= l.unitDoneAt(left) + SET_TAIL_S) {
                    milestoneSet = s.runSerial
                    sink(JcEvent.SetComplete(s.runSerial))
                }
            }
            else -> Unit
        }
    }

    // ---- body-frame content ---------------------------------------------------------------

    private fun contentFrame(s: JcUiState, now: Long, t: Float) {
        val f = shownFrame
        val b = body ?: return
        val visible = f != null && s.phase.practising && !(s.phase == Phase.ALIGNING)
        if (!visible) {
            b.enabled = false
            discLeft?.hide()
            discRight?.hide()
            digit?.hide()
            return
        }
        f!!
        if (!b.enabled) b.enabled = true
        val fade = if (fadeStartMs == NEVER) 1f else ((now - fadeStartMs) / FADE_MS).coerceIn(0f, 1f)
        if (fade >= 1f) fadeStartMs = NEVER
        val driftK = if (s.drifted) DRIFT_OPACITY else 1f
        b.setOpacity(fade * driftK)
        val l = lessonNow(s.course)
        val z = shownParams.zFront
        val face = -f.ahead
        val toStage: (P2) -> Vec3 = { p -> f.toStage(p, z) }
        val test = s.testTexture
        // paths, rings, discs per hand
        for (h in Hand.entries) {
            val path = l.paths[h]
            val pv = paths[h] ?: continue
            val rv = rings[h] ?: continue
            val dv = handDiscs[h] ?: continue
            if (path == null) {
                pv.hide()
                rv.hide()
                dv.hide()
                continue
            }
            val cue = l.cueAt(h, t)
            pv.draw(path.hold, path.release, path.flightPoints(), toStage, face, cue.pathLit)
            rv.show(f.toStage(path.peak, z), f.rotation, cue.ringScale)
            dv.show(f.toStage(path.hold, z + 0.001f), f.rotation, cue.carry, cue.catchSoon, cue.caught, test)
        }
        // scarves
        val states = l.scarvesAt(t)
        val breathe = if (s.phase == Phase.PAUSED && !s.menuOpen) 0.55f + 0.45f * ((1 + cos(2 * Math.PI * (now % BREATHE_MS) / BREATHE_MS)).toFloat() / 2f) else 1f
        for ((i, view) in scarves.withIndex()) {
            val st = states.getOrNull(i)
            if (st == null) {
                view.hide()
                continue
            }
            val sway = if (st.flying && st.swayPhase > 0f) SWAY_M * sin(2 * Math.PI * SWAY_HZ * st.swayPhase).toFloat() else 0f
            // centred on its point (in a hand: on the disc centre), 1 cm in front of the path plane / disc
            val at = f.toStage(P2(st.at.x + sway, st.at.y), z - SCARF_FRONT_M + st.dz + 0.002f * i)
            view.show(at, yawTo(at), st.scale, s.course.colorOf(i), breathe)
        }
        // count-in digit over the hand disc of the beat (design: 4 3 2 1 over R L R L), popping on each beat
        val beat = if (s.phase == Phase.COUNT_IN) l.countInBeat(t + Lesson.COUNT_IN_TOTAL) else null
        if (beat != sentBeat) {
            sentBeat = beat
            sink(JcEvent.CountInBeat(beat))
        }
        val dg = digit
        if (beat != null && dg != null) {
            val hand = l.countInHand(beat)
            val hold = l.hold(hand)
            val into = (t + Lesson.COUNT_IN_TOTAL) - beat * Lesson.COUNT_IN_S
            val pop = if (into < 0.12f) 1f + 0.25f * (1f - into / 0.12f) else 1f
            val at = f.toStage(P2(hold.x, hold.y + DIGIT_ABOVE_M + 0.04f), z - 0.01f)
            dg.show(at, yawTo(at), Lesson.COUNT_IN_BEATS - beat, pop, test)
        } else {
            dg?.hide()
        }
        discsFrame(s, f, test)
    }

    /**
     * Dwell discs (design `input.dwell.placement`): x = +/-(S/2 + 0.115), y = -0.175, z = zFront - 0.05 in the body frame
     * (P5: the right one at y = -0.05). Drifted (S10): both follow the head instead (left = bring it here).
     */
    private fun discsFrame(s: JcUiState, f: BodyFrame, test: Boolean) {
        val dl = discLeft ?: return
        val dr = discRight ?: return
        if (s.menuOpen || !s.phase.practising) {
            dl.hide()
            dr.hide()
            return
        }
        val frame = if (s.drifted) BodyFrame(head.position, headYaw()) else f
        val p = shownParams
        val x = p.shoulder / 2f + 0.115f
        val z = p.zFront - 0.05f
        val left = frame.toStage(-x, DISC_Y, z)
        val leftIcon =
            when {
                s.drifted -> Textures.Icon.HERE
                s.phase == Phase.COUNT_IN || s.phase == Phase.FOLLOWING -> Textures.Icon.PAUSE
                s.phase == Phase.SET_DONE -> Textures.Icon.REPLAY
                else -> Textures.Icon.PLAY
            }
        dl.show(left, yawTo(left), leftIcon, progressOf(DwellTarget.LEFT_DISC), test)
        val rightShown = !s.spike && s.course.next != null && (s.phase == Phase.DEMO || s.phase == Phase.PAUSED || s.phase == Phase.SET_DONE)
        if (rightShown) {
            val y = if (s.course == Course.P5) DISC_Y_P5 else DISC_Y
            val right = frame.toStage(x, y, z)
            dr.show(right, yawTo(right), Textures.Icon.NEXT, progressOf(DwellTarget.RIGHT_DISC), test)
        } else {
            dr.hide()
        }
    }

    private fun progressOf(target: String): Float = if (sentTarget == target) currentProgress else 0f

    private var currentProgress = 0f

    // ---- drift ----------------------------------------------------------------------------

    /**
     * Drift (design `rules.bodyFrame.drift`): head more than 0.30 m / 25 degrees off F for 1.5 s -> S10; while drifted
     * a head that is still for 1.0 s (< 0.15 m/s, < 20 deg/s) takes F along (0.5 s slide); stepping back within the
     * limits ends it. Reading the panel (the head ray on it) does not count as drifting.
     */
    private fun driftFrame(s: JcUiState, now: Long, dt: Float) {
        val f = s.frame
        val active = f != null && s.phase.practising && !s.debugHold && hasHead
        if (!active) {
            driftSinceMs = NEVER
            stillSinceMs = NEVER
            backSinceMs = NEVER
            driftSent = s.drifted
            prevHead = null
            return
        }
        f!!
        val p = head.position
        val yaw = headYaw()
        val d = Vec3(p.x - f.origin.x, 0f, p.z - f.origin.z).length
        val a = abs(JcViewModel.angle(yaw - f.yaw))
        val out = d > JcViewModel.DRIFT_M || a > JcViewModel.DRIFT_RAD
        // reading the panel or looking at a dwell disc (both stand 42 degrees off the body frame) is not drifting
        val reading = lookingAtPanel() || lastGazeHit != null
        if (!s.drifted) {
            if (out && !reading) {
                if (driftSinceMs == NEVER) driftSinceMs = now
                if (now - driftSinceMs >= DRIFT_AFTER_MS && !driftSent) {
                    driftSent = true
                    JcLog.i("DRIFT on d=${JcLog.f(d)} yaw=${JcLog.f(Math.toDegrees(a.toDouble()).toFloat(), 1)}")
                    sink(JcEvent.DriftChanged(true))
                }
            } else {
                driftSinceMs = NEVER
                driftSent = false
            }
            prevHead = p
            prevYaw = yaw
            return
        }
        driftSent = true
        // drifted: back inside -> end; still for 1 s -> take the frame along
        if (!out) {
            if (backSinceMs == NEVER) backSinceMs = now
            if (now - backSinceMs >= 300L) {
                JcLog.i("DRIFT off (back)")
                driftSinceMs = NEVER
                sink(JcEvent.DriftChanged(false))
            }
            return
        }
        backSinceMs = NEVER
        val ph = prevHead
        val speed = if (ph == null || dt <= 0f) 0f else Vec3(p.x - ph.x, 0f, p.z - ph.z).length / dt
        val yawRate = if (dt <= 0f) 0f else abs(JcViewModel.angle(yaw - prevYaw)) / dt
        prevHead = p
        prevYaw = yaw
        if (speed < STILL_SPEED && yawRate < STILL_YAW_RATE && !reading) {
            if (stillSinceMs == NEVER) stillSinceMs = now
            if (now - stillSinceMs >= STILL_MS) {
                stillSinceMs = NEVER
                JcLog.i("DRIFT reframe at ${JcLog.pos(p)}")
                sink(JcEvent.Reframed(BodyFrame(p, yaw)))
            }
        } else {
            stillSinceMs = NEVER
        }
    }

    // ---- gaze + dwell ---------------------------------------------------------------------

    private class Target(val id: String, val center: Vec3, val normal: Vec3, val halfW: Float, val halfH: Float, val right: Vec3, val round: Boolean)

    /**
     * Gaze ray: the eyes when they give a fresh sample (eye-tracking permission, device support), else the head direction
     * (S13 for good, or for a moment: amber dot). Hits on the dwell discs, the lesson cards or the welcome button are
     * counted here every frame in real time (system hover is not reported to the app: fold-guide finding).
     */
    private fun gazeFrame(s: JcUiState, now: Long) {
        val dot = gazeDot ?: return
        if (!hasHead) return
        val eye = eyeTracker.pose(now)
        if (eye == null) {
            eyeGoodSinceMs = NEVER
            if (eyeBadSinceMs == NEVER) eyeBadSinceMs = now
            if (!usingHeadNow && now - eyeBadSinceMs >= EYE_BAD_MS) usingHeadNow = true
        } else {
            eyeBadSinceMs = NEVER
            if (eyeGoodSinceMs == NEVER) eyeGoodSinceMs = now
            if (usingHeadNow && now - eyeGoodSinceMs >= EYE_GOOD_MS) usingHeadNow = false
        }
        val ray = if (!usingHeadNow && eye != null) eye else head
        val origin = ray.position
        val dir = ray.forward
        val targets = targets(s)
        // state changed: a target already under the gaze does not start a dwell until the gaze leaves it
        val key = "${s.phase}|${s.menuOpen}|${s.course}|${s.drifted}|${s.demoPlaying}"
        var hitId: String? = null
        var hitPoint: Vec3? = null
        var best = Float.MAX_VALUE
        for (tg in targets) {
            val denom = dir.dot(tg.normal)
            if (abs(denom) < 1e-4f) continue
            val dist = (tg.center - origin).dot(tg.normal) / denom
            if (dist <= 0f) continue
            val hit = origin + dir * dist
            val local = hit - tg.center
            val up = tg.normal.cross(tg.right)
            val lx = local.dot(tg.right)
            val ly = local.dot(up)
            val inside = if (tg.round) lx * lx + ly * ly <= tg.halfW * tg.halfW else abs(lx) <= tg.halfW && abs(ly) <= tg.halfH
            if (inside && dist < best) {
                best = dist
                hitId = tg.id
                hitPoint = hit
            }
        }
        lastGazeHit = hitId ?: nearDisc(origin, dir)
        if (key != dwellKey) {
            dwellKey = key
            dwellBlocked = hitId
            dwellStartMs = NEVER
        }
        if (dwellBlocked != null && hitId != dwellBlocked) dwellBlocked = null
        val debug = s.debugDwell
        val dwellS = s.settings.dwell.seconds
        var held = 0f
        var target: String? = null
        if (debug != null) {
            target = debugTarget(s)
            held = debug
        } else if (hitId != null && hitId != dwellBlocked && now >= dwellCoolUntil) {
            if (hitId != dwellTarget) {
                dwellTarget = hitId
                dwellStartMs = now
            }
            target = hitId
            held = (now - dwellStartMs) / 1000f
        } else {
            dwellTarget = null
            dwellStartMs = NEVER
        }
        val progress = if (target == null) 0f else ((held - DWELL_SHOW_S) / (dwellS - DWELL_SHOW_S)).coerceIn(0f, 1f)
        currentProgress = progress
        val stepped = (progress * 10f).toInt() / 10f
        val shownTarget = if (progress > 0f) target else null
        if (shownTarget != sentTarget || stepped != sentProgress) {
            sentTarget = shownTarget
            sentProgress = stepped
            sink(JcEvent.DwellChanged(shownTarget, stepped))
        }
        if (debug == null && target != null && held >= dwellS) {
            JcLog.i("DWELL done target=$target source=${if (usingHeadNow || eye == null) "head" else "eye"}")
            dwellStartMs = NEVER
            dwellTarget = null
            dwellCoolUntil = now + DWELL_COOL_MS
            dwellBlocked = target
            sink(JcEvent.DwellFired(target, if (usingHeadNow || eye == null) "head" else "eye"))
        }
        // gaze dot: on the path plane (or the panel plane in S01 / S02), only near a target
        val near = targets.minOfOrNull { tg -> hitPoint?.distanceTo(tg.center) ?: planeHit(origin, dir, tg)?.distanceTo(tg.center) ?: Float.MAX_VALUE } ?: Float.MAX_VALUE
        val point = hitPoint ?: targets.firstOrNull()?.let { planeHit(origin, dir, it) }
        if (point != null && near <= DOT_NEAR_M) {
            val fadeIn = 1f
            dot.show(point + (head.position - point).normalized() * 0.004f, yawTo(point), s.gazeAmber && !s.headFallback, fadeIn)
        } else {
            dot.hide()
        }
    }

    /** A dwell disc within 0.15 m of the gaze line (the head turned to it, a little off). */
    private fun nearDisc(origin: Vec3, dir: Vec3): String? {
        for (d in listOfNotNull(discLeft, discRight)) {
            if (!d.shown) continue
            val c = runCatching { d.root.convertPositionTo(Vector3.ZERO, stageRoot) }.getOrNull()?.domain() ?: continue
            val to = c - origin
            val along = to.dot(dir)
            if (along > 0f && (to - dir * along).length < 0.15f) return d.target
        }
        return null
    }

    private fun planeHit(origin: Vec3, dir: Vec3, tg: Target): Vec3? {
        val denom = dir.dot(tg.normal)
        if (abs(denom) < 1e-4f) return null
        val dist = (tg.center - origin).dot(tg.normal) / denom
        return if (dist <= 0f) null else origin + dir * dist
    }

    private fun debugTarget(s: JcUiState): String =
        when (s.phase) {
            Phase.WELCOME -> DwellTarget.WELCOME
            Phase.PICKER -> DwellTarget.card(s.pickerCourse)
            else -> DwellTarget.LEFT_DISC
        }

    private fun targets(s: JcUiState): List<Target> {
        val out = ArrayList<Target>()
        val rig = rig ?: return out
        when {
            s.phase == Phase.WELCOME -> rig[PanelSpec.WELCOME]?.takeIf { it.shown }?.let { slot ->
                val (w, _) = PanelSpec.WELCOME_SIZE
                out += panelRect(slot, DwellTarget.WELCOME, PanelSpec.WELCOME_SIZE, w / 2f, PanelSpec.WELCOME_BUTTON_TOP + PanelSpec.WELCOME_BUTTON_H / 2f, w - 2 * PanelSpec.PAD, PanelSpec.WELCOME_BUTTON_H)
            }
            s.phase == Phase.PICKER && !s.menuOpen -> rig[PanelSpec.PICKER]?.takeIf { it.shown }?.let { slot ->
                for ((i, c) in Course.entries.withIndex()) {
                    if (s.spike && c != Course.P1) continue
                    val (cx, cy) = PanelSpec.cardCenterDp(i)
                    out += panelRect(slot, DwellTarget.card(c), PanelSpec.PICKER_SIZE, cx, cy, PanelSpec.CARD_W, PanelSpec.CARD_H)
                }
            }
            else -> {
                for (d in listOfNotNull(discLeft, discRight)) {
                    if (!d.shown) continue
                    val c = d.root.let { e -> runCatching { e.convertPositionTo(Vector3.ZERO, stageRoot) }.getOrNull()?.domain() } ?: continue
                    val n = Vec3(head.position.x - c.x, 0f, head.position.z - c.z).normalized()
                    out += Target(d.target, c, n, DISC_HIT_R, DISC_HIT_R, Vec3.UP.cross(n).normalized(), round = true)
                }
            }
        }
        return out
    }

    /** A rectangle on a shown panel: centre ([cxDp], [cyDp]) from the panel's top-left, [wDp] x [hDp]. */
    private fun panelRect(slot: PanelRig.Slot, id: String, size: Pair<Float, Float>, cxDp: Float, cyDp: Float, wDp: Float, hDp: Float): Target {
        val k = PanelSpec.M_PER_DP
        val (w, h) = size
        val local = Vec3((cxDp - w / 2f) * k, (h / 2f - cyDp) * k, 0f)
        val c = slot.position + slot.rotation.rotate(local)
        val n = slot.rotation.rotate(Vec3(0f, 0f, 1f))
        val right = slot.rotation.rotate(Vec3.RIGHT)
        return Target(id, c, n, wDp * k / 2f, hDp * k / 2f, right, round = false)
    }

    /** True when the head's view line falls on the shown control panel or menu (+5 cm). */
    private fun lookingAtPanel(): Boolean {
        val rig = rig ?: return false
        val id = shownPanel ?: return false
        val slot = rig[id]?.takeIf { it.shown } ?: return false
        val (w, h) = PanelSpec.size(id)
        val k = PanelSpec.M_PER_DP
        val n = slot.rotation.rotate(Vec3(0f, 0f, 1f))
        val tg = Target(id, slot.position, n, w * k / 2f + 0.05f, h * k / 2f + 0.05f, slot.rotation.rotate(Vec3.RIGHT), round = false)
        val hit = planeHit(head.position, head.forward, tg) ?: return false
        val local = hit - tg.center
        val up = tg.normal.cross(tg.right)
        return abs(local.dot(tg.right)) <= tg.halfW && abs(local.dot(up)) <= tg.halfH
    }

    // =========================================================================================
    // panels (design `rules.panelFollow`)
    // =========================================================================================

    private fun headYaw(): Float {
        val f = flatForward()
        return atan2(-f.x, -f.z)
    }

    private fun flatForward(): Vec3 {
        val f = head.forward
        val v = Vec3(f.x, 0f, f.z)
        return if (v.length < 1e-3f) Vec3.AHEAD else v.normalized()
    }

    private fun dirOfYaw(yaw: Float): Vec3 = Vec3(-sin(yaw), 0f, -cos(yaw))

    /** Yaw that turns a plate's +Z towards the head (billboard about the vertical). */
    private fun yawTo(at: Vec3): Float {
        val d = head.position - at
        return atan2(d.x, d.z)
    }

    /**
     * Which panel shows and where. S01 / S02 and the first step of S03 from the picker: in front, 0.7 m, 0.05 m below the
     * eyes, following the view with a +/-30 degree dead zone. Otherwise 42 degrees left, 0.9 m, 0.05 m above the eyes,
     * yaw only (never pitch), still while its centre stays between 67 degrees left and 25 degrees right of the view;
     * outside for 0.5 s -> slides back in 0.4 s. A switch between the two places glides over 0.4 s. Contents of other
     * heights hang from the same top edge.
     */
    private fun placePanels(rig: PanelRig, now: Long) {
        if (!hasHead) return
        val s = ui
        val id =
            when {
                s.phase == Phase.WELCOME -> PanelSpec.WELCOME
                s.phase == Phase.PICKER && !s.menuOpen -> PanelSpec.PICKER
                s.menuOpen -> PanelSpec.MENU
                else -> PanelSpec.CONTROL
            }
        val front = s.phase == Phase.WELCOME || s.phase == Phase.PICKER || (s.phase == Phase.ALIGNING && s.alignFromFront && alignSerialSeen >= 0)
        val eye = s.frame?.origin?.y ?: if (eyeHeight.isNaN()) head.position.y else eyeHeight
        val yawNow = headYaw()
        if (frontMode != front) {
            if (frontMode != null) {
                modeFrom = lastPanelPos?.let { it to lastPanelYaw }
                modeStartMs = now
            }
            frontMode = front
            anchorYaw = yawNow
            anchorHead = head.position
            slideStartMs = NEVER
            outSinceMs = NEVER
        }
        if (anchorYaw.isNaN()) {
            anchorYaw = yawNow
            anchorHead = head.position
        }
        val offsetDeg = if (front) 0f else PANEL_LEFT_DEG
        if (slideStartMs != NEVER) {
            val q = ((now - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = Ease.inOutCubic(q)
            anchorYaw = slideFromYaw + JcViewModel.angle(yawNow - slideFromYaw) * e
            anchorHead = slideFromHead.lerp(head.position, e)
            if (q >= 1f) slideStartMs = NEVER
        } else {
            // where the panel centre is relative to the view now (+ = left)
            val rel = Math.toDegrees(JcViewModel.angle(anchorYaw + rad(offsetDeg) - yawNow).toDouble()).toFloat()
            val moved = Vec3(head.position.x - anchorHead.x, 0f, head.position.z - anchorHead.z).length
            val inside = if (front) abs(rel) <= FRONT_DEAD_DEG && moved < FOLLOW_MOVE_M else rel <= SIDE_LEFT_LIMIT_DEG && rel >= -SIDE_RIGHT_LIMIT_DEG && moved < FOLLOW_MOVE_M
            if (inside || s.debugHold && s.phase != Phase.ALIGNING) {
                outSinceMs = NEVER
            } else if (outSinceMs == NEVER) {
                outSinceMs = now
            } else if (now - outSinceMs >= FOLLOW_OUT_MS) {
                slideFromYaw = anchorYaw
                slideFromHead = anchorHead
                slideStartMs = now
                outSinceMs = NEVER
                JcLog.i("FOLLOW panel slide rel=${JcLog.f(rel, 1)} moved=${JcLog.f(moved)} front=$front")
            }
        }
        val panelYaw = anchorYaw + rad(offsetDeg)
        val dist = if (front) FRONT_DISTANCE_M else SIDE_DISTANCE_M
        val (_, h) = PanelSpec.size(id)
        val k = PanelSpec.M_PER_DP
        val centerY =
            if (front) eye + FRONT_BELOW_EYE_M
            else {
                // control panel centre at eye + 0.05; other contents keep its top edge
                val top = eye + SIDE_ABOVE_EYE_M + PanelSpec.CONTROL_SIZE.second * k / 2f
                top - h * k / 2f
            }
        var at = Vec3(anchorHead.x, centerY, anchorHead.z) + dirOfYaw(panelYaw) * dist
        var yaw = panelYaw
        if (modeStartMs != NEVER) {
            val from = modeFrom
            val q = ((now - modeStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            if (from != null && q < 1f) {
                val e = Ease.inOutCubic(q)
                at = from.first.lerp(at, e)
                yaw = from.second + JcViewModel.angle(yaw - from.second) * e
            } else {
                modeStartMs = NEVER
            }
        }
        lastPanelPos = at
        lastPanelYaw = yaw
        val rot = Quat4.axisAngle(Vec3.UP, yaw)
        if (shownPanel != id) {
            shownPanel?.let { rig[it]?.hide() }
            shownPanel = id
            JcLog.i("PANEL show id=$id front=$front")
        }
        rig[id]?.place(at, rot, PanelSpec.SCALE)
        for (other in listOf(PanelSpec.CONTROL, PanelSpec.WELCOME, PanelSpec.PICKER, PanelSpec.MENU)) if (other != id) rig[other]?.hide()
    }

    // =========================================================================================
    // system pointer (gaze + pinch, controller trigger, emulator mouse)
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        if (target == null) {
            JcLog.i("TAP other target=none")
            return
        }
        when {
            discLeft?.isGrab(target) == true -> sink(JcEvent.DwellFired(DwellTarget.LEFT_DISC, "tap"))
            discRight?.isGrab(target) == true -> sink(JcEvent.DwellFired(DwellTarget.RIGHT_DISC, "tap"))
            ui.menuOpen -> {
                JcLog.i("TAP outside menu target=$name")
                sink(JcEvent.TappedOutside)
            }
            else -> JcLog.i("TAP other target=$name")
        }
    }

    // =========================================================================================
    // logs + debug probe
    // =========================================================================================

    private fun logGeom() {
        val f = shownFrame ?: return
        val l = lessonNow(ui.course)
        val z = shownParams.zFront
        val sb = StringBuilder("JC-GEOM course=${l.course.number}")
        for ((h, p) in l.paths) {
            sb.append(
                String.format(
                    Locale.US,
                    " %s[hold=(%.3f,%.3f) rel=(%.3f,%.3f) peak=(%.3f,%.3f) catch=(%.3f,%.3f)]",
                    h.name.first(), p.hold.x, p.hold.y, p.release.x, p.release.y, p.peak.x, p.peak.y, p.catch.x, p.catch.y,
                )
            )
        }
        val x = shownParams.shoulder / 2f + 0.115f
        sb.append(String.format(Locale.US, " discs=(+-%.3f,%.3f,%.3f) z=%.2f", x, DISC_Y, z - 0.05f, z))
        sb.append(" peakStageR=${JcLog.pos(l.paths[Hand.RIGHT]?.let { f.toStage(it.peak, z) })} eye=${JcLog.f(f.origin.y, 3)}")
        JcLog.i(sb.toString())
    }

    override fun poseLine(): String =
        "POSE hmd pos=${JcLog.pos5(head.position)} ${JcLog.quat5(head.rotation)} fwd=${JcLog.pos5(head.forward)} valid=$hasHead eye=${JcLog.f(eyeHeight, 3)} usingHead=$usingHeadNow"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            val rel = Math.toDegrees(JcViewModel.angle(atan2(-(slot.position - head.position).x, -(slot.position - head.position).z) - headYaw()).toDouble())
            val pitch = Math.toDegrees(asin(((slot.position - head.position).normalized().y).coerceIn(-1f, 1f)).toDouble())
            String.format(Locale.US, "PANEL id=%s pos=%s dist=%.3f az=%.1f el=%.1f size_dp=%.0fx%.0f", slot.id, JcLog.pos5(slot.position), slot.position.distanceTo(head.position), rel, pitch, slot.widthDp, slot.heightDp)
        }
    }

    override fun geomLine(): String {
        logGeom()
        return "GEOM logged"
    }

    override fun clockLine(): String {
        val l = lessonNow(ui.course)
        return String.format(Locale.US, "CLOCK t=%.3f count=%d audio=%s armedDemo=%d armedRun=%d", lastT, l.countAt(lastT), audio.lessonTime()?.let { String.format(Locale.US, "%.3f", it) } ?: "null", armedDemo, armedRun)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REST = -1000f
        private const val BACKSTOP = "backstop"
        private const val REBIND_DELAY_MS = 150L
        private const val HEAD_SAMPLE_MS = 100L
        private const val BLOCK_AFTER_MS = 2_000L
        private const val FAST_FRAME_MS = 40L
        private const val MESH_GAP_SLOW_MS = 2_000L
        private const val TEXTURE_GAP_SLOW_MS = 1_200L
        private const val ENTITY_GAP_SLOW_MS = 600L
        private const val EYE_SAMPLE_MS = 1_000L
        private const val EYE_POLL_MS = 500L

        private const val ALIGN_MS = 1_000f
        private const val ALIGN_TRIES = 3
        private const val FADE_MS = 500f
        private const val PARAMS_EASE_MS = 300f
        private const val FRAME_SLIDE_MS = 500f
        private const val DEMO_LEAD_S = 0.3f
        private const val DEMO_TAIL_S = 0.5f
        private const val SET_TAIL_S = 0.3f
        private const val BREATHE_MS = 1_200L
        private const val SWAY_M = 0.02f
        private const val SWAY_HZ = 1.5
        private const val DIGIT_ABOVE_M = 0.08f
        private const val SCARF_FRONT_M = 0.012f
        private const val DRIFT_OPACITY = 0.6f

        private const val DRIFT_AFTER_MS = 1_500L
        private const val STILL_MS = 1_000L
        private const val STILL_SPEED = 0.15f
        private val STILL_YAW_RATE = Math.toRadians(20.0).toFloat()

        private const val DISC_Y = -0.175f
        private const val DISC_Y_P5 = -0.05f
        private const val DISC_HIT_R = 0.075f
        private const val DWELL_SHOW_S = 0.3f
        private const val DWELL_COOL_MS = 800L
        private const val DOT_NEAR_M = 0.25f
        private const val EYE_BAD_MS = 300L
        private const val EYE_GOOD_MS = 500L

        private const val PANEL_LEFT_DEG = 42f
        private const val SIDE_DISTANCE_M = 0.9f
        private const val SIDE_ABOVE_EYE_M = 0.05f
        private const val SIDE_LEFT_LIMIT_DEG = 67f
        private const val SIDE_RIGHT_LIMIT_DEG = 25f
        private const val FRONT_DISTANCE_M = 0.7f
        private const val FRONT_BELOW_EYE_M = -0.05f
        private const val FRONT_DEAD_DEG = 30f
        private const val FOLLOW_MOVE_M = 0.5f
        private const val FOLLOW_OUT_MS = 500L
        private const val SLIDE_MS = 400f
        private const val DIALOG_DISTANCE_M = 0.6f

        private fun rad(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()
    }
}
