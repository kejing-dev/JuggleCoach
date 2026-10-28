package io.embodit.jugglecoach.ui.jc.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import io.embodit.jugglecoach.domain.juggle.P2
import io.embodit.jugglecoach.domain.model.Hand
import io.embodit.jugglecoach.domain.model.Quat4
import io.embodit.jugglecoach.domain.model.Vec3
import io.embodit.jugglecoach.platform.JcLog
import io.embodit.jugglecoach.ui.theme.JcColors
import kotlin.math.PI

/** Plate rotation: a mesh built in the local XZ plane facing +Y turned to face +Z (then a facing rotation aims +Z at the user). */
internal val PLATE: Quat4 = Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat())

/** Unlit, tone mapping off: the design colours are exact. Every appearance is one scene-lifetime material. */
internal class JcMaterials(private val shared: SharedResources) {
    val pathR: Material = sheer(JcColors.ScarfCoral, 0.85f)
    val pathL: Material = sheer(JcColors.ScarfSky, 0.85f)
    val pathRLit: Material = sheer(JcColors.ScarfCoral, 1f)
    val pathLLit: Material = sheer(JcColors.ScarfSky, 1f)
    val ring: Material = sheer(JcColors.ArcWhite, 0.95f)
    val ringFlash: Material = sheer(JcColors.ArcWhite, 1f)
    val rim: Material = sheer(JcColors.ArcWhite, 0.9f)
    val glowR: Material = sheer(JcColors.ScarfCoral, 0.6f)
    val glowL: Material = sheer(JcColors.ScarfSky, 0.6f)
    val flash: Material = sheer(JcColors.ArcWhite, 0.85f)
    val progress: Material = sheer(JcColors.Interaction, 1f)
    val reticle: Material = sheer(JcColors.ArcWhite, 0.9f)
    val gaze: Material = sheer(JcColors.ArcWhite, 0.6f)
    val gazeAmber: Material = sheer(JcColors.Amber, 0.75f)

    /** Scarves: outer feather 30 %, core 45 % (soft edge), one pair per colour slot (orange, blue, yellow). */
    val scarfOuter: List<Material> = JcColors.scarves.map { sheer(it, 0.30f) }
    val scarfCore: List<Material> = JcColors.scarves.map { sheer(it, 0.45f) }

    // ---- textured plates, made by [buildTextures] (one at a time: TextureResource.create waits for the render thread)
    var handBase = HashMap<Hand, Material>()
    var handLit = HashMap<Hand, Material>()
    var icons = HashMap<Textures.Icon, Material>()
    var digits = HashMap<Int, Material>()
    var testF: Material? = null

    suspend fun buildTextures(pace: suspend () -> Unit) {
        for (h in Hand.entries) {
            textured(Textures.hand(h, 0.30f), "hand_$h")?.let { handBase[h] = it }
            pace()
            textured(Textures.hand(h, 0.60f), "hand_lit_$h")?.let { handLit[h] = it }
            pace()
        }
        for (i in Textures.Icon.entries) {
            textured(Textures.icon(i), "icon_$i")?.let { icons[i] = it }
            pace()
        }
        for (d in 1..4) {
            textured(Textures.digit(d), "digit_$d")?.let { digits[d] = it }
            pace()
        }
        testF = textured(Textures.testF(), "testF")
    }

    /** A see-through textured plate (alpha from the bitmap when the platform honours it). */
    private fun textured(bitmap: Bitmap, name: String): Material? =
        runCatching {
                val texture = shared.keep(TextureResource.create(bitmap))
                shared.keep(
                    UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                        setBaseColor(Color4(1f, 1f, 1f, 1f))
                        setBaseColorTexture(texture)
                        runCatching { setApplyToneMapping(false) }
                        setCullingMode(MaterialCullingMode.NONE)
                        setDepthWrite(false)
                    }
                )
            }
            .onFailure { JcLog.w("TEXTURE $name failed: $it") }
            .getOrNull()

    /** A see-through unlit material: the colour is opaque, the material's opacity carries the share. */
    private fun sheer(color: Color, opacity: Float): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )
}

/** The few shared meshes; everything else is a scaled / turned instance (no mesh is built after start-up). */
internal class JcMeshes {
    lateinit var band: MeshResource
    lateinit var plate: MeshResource
    lateinit var ring: MeshResource
    lateinit var piece: MeshResource
    lateinit var arrow: MeshResource
    lateinit var feather: MeshResource

    suspend fun build(shared: SharedResources, pace: suspend () -> Unit) {
        band = shared.keep(MeshFactory.unitBand("band"))
        pace()
        plate = shared.keep(MeshFactory.discUv(0.5f, "plate"))
        pace()
        ring = shared.keep(MeshFactory.ring(0.8f, 1.0f, "ring", segments = 40))
        pace()
        piece = shared.keep(MeshFactory.arcPiece(0.86f, 1.0f, (2 * PI / PIECES).toFloat(), "piece"))
        pace()
        arrow = shared.keep(MeshFactory.arrowHead("arrow"))
        pace()
        feather = shared.keep(MeshFactory.feather("feather"))
    }

    companion object {
        const val PIECES = 24
    }
}

private fun model(mesh: MeshResource, material: Material, name: String): Entity = ModelEntity(mesh, material).named(name)

private fun Entity.show(on: Boolean) {
    if (enabled != on) enabled = on
}

/**
 * A rim of [JcMeshes.PIECES] arc pieces (unit radius, scaled by the parent), filled clockwise from 12 o'clock as the
 * user sees it. Lives in a plate (XZ) frame.
 */
internal class Rim(parent: Entity, meshes: JcMeshes, material: Material, name: String) {
    private val pieces = ArrayList<Entity>(JcMeshes.PIECES)
    private var lit = -1

    init {
        val step = 2 * PI / JcMeshes.PIECES
        for (k in 0 until JcMeshes.PIECES) {
            val e = model(meshes.piece, material, "${name}_$k")
            // piece k spans 90 - (k+1)*step .. 90 - k*step degrees as the user sees it: from 12 o'clock clockwise
            e.setLocal(position = Vec3(0f, 0.001f, 0f), rotation = Quat4.axisAngle(Vec3.UP, (PI / 2 - step * k).toFloat()))
            e.enabled = false
            parent.addChild(e)
            pieces += e
        }
    }

    fun fill(q: Float) {
        val on = Math.round(q.coerceIn(0f, 1f) * JcMeshes.PIECES)
        if (on == lit) return
        lit = on
        for ((k, e) in pieces.withIndex()) e.show(k < on)
    }
}

/**
 * One hand's path (design `entities3d.arcTubes`): the carry as a thin solid line (Ø 6 mm) and the flight as dashes
 * (Ø 12 mm, 4 cm on / 3 cm off), all flat strips in the path plane turned to the user. While lit (carry .. peak of a
 * throw from this hand) the strips switch to the 100 % material and 1.3x width.
 */
internal class PathView(parent: Entity, meshes: JcMeshes, private val m: JcMaterials, private val hand: Hand) {
    val root: Entity = Entity().named("path_$hand")
    private val carry = Strip(root, meshes.band, normal(), "carry_$hand")
    private val dashes = ArrayList<Strip>()
    private var used = 0
    private var litShown: Boolean? = null

    init {
        parent.addChild(root)
        repeat(MAX_DASHES) { dashes += Strip(root, meshes.band, normal(), "dash_${hand}_$it") }
        root.enabled = false
    }

    private fun normal(): Material = if (hand == Hand.RIGHT) m.pathR else m.pathL

    private fun bright(): Material = if (hand == Hand.RIGHT) m.pathRLit else m.pathLLit

    /** [toStage] maps path-plane points; [face] is the unit normal towards the user. */
    fun draw(hold: P2, release: P2, flight: List<P2>, toStage: (P2) -> Vec3, face: Vec3, lit: Boolean) {
        root.show(true)
        val k = if (lit) LIT_WIDTH else 1f
        carry.draw(toStage(hold), toStage(release), CARRY_W * k, face)
        // dashes along the arc length
        var i = 0
        var pos = 0f
        var seg = 0
        val lengths = FloatArray(flight.size)
        for (j in 1 until flight.size) lengths[j] = lengths[j - 1] + flight[j - 1].distanceTo(flight[j])
        val total = lengths.last()
        fun at(s: Float): P2 {
            while (seg < flight.size - 2 && lengths[seg + 1] < s) seg++
            val a = lengths[seg]
            val b = lengths[seg + 1]
            val t = if (b - a < 1e-6f) 0f else (s - a) / (b - a)
            return flight[seg].lerp(flight[seg + 1], t)
        }
        while (pos < total && i < dashes.size) {
            val end = minOf(total, pos + DASH_ON)
            dashes[i].draw(toStage(at(pos)), toStage(at(end)), FLIGHT_W * k, face)
            i++
            pos = end + DASH_OFF
        }
        for (j in i until used) dashes[j].hide()
        used = i
        if (litShown != lit) {
            litShown = lit
            val mat = if (lit) bright() else normal()
            carry.setMaterial(mat)
            dashes.forEach { it.setMaterial(mat) }
        }
    }

    fun hide() = root.show(false)

    fun destroy() {
        runCatching { root.destroy(true) }
    }

    private companion object {
        const val MAX_DASHES = 40
        const val CARRY_W = 0.006f
        const val FLIGHT_W = 0.012f
        const val DASH_ON = 0.04f
        const val DASH_OFF = 0.03f
        const val LIT_WIDTH = 1.3f
    }
}

/** Peak ring Ø 8 cm, 8 mm wide, white, facing the user, with a small up arrow 3 cm above (design `entities3d.peakRings`). */
internal class PeakRingView(parent: Entity, meshes: JcMeshes, m: JcMaterials, name: String) {
    val root: Entity = Entity().named(name)
    private val plate = Entity()
    private val arrow: Entity

    init {
        parent.addChild(root)
        root.addChild(plate)
        plate.setLocal(rotation = PLATE)
        plate.addChild(model(meshes.ring, m.ring, "${name}_ring").also { it.setLocal(scale = Vec3(0.044f, 1f, 0.044f)) })
        arrow = model(meshes.arrow, m.ring, "${name}_arrow")
        root.addChild(arrow)
        // apex up: the arrow's +Z apex turned to +Y, face to +Z
        arrow.setLocal(position = Vec3(0f, 0.055f, 0f), rotation = PLATE * Quat4.axisAngle(Vec3.UP, PI.toFloat()), scale = Vec3(0.025f, 1f, 0.025f))
        root.enabled = false
    }

    fun show(at: Vec3, facing: Quat4, scale: Float) {
        root.show(true)
        root.setLocal(position = at, rotation = facing, scale = Vec3(scale, scale, scale))
    }

    fun hide() = root.show(false)

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/**
 * Hand disc Ø 14 cm (design `entities3d.handDiscs`): hand colour 30 % with the hand outline; 60 % plus a 2 cm halo
 * when a scarf lands within 0.3 s; a white flash for 0.1 s at the catch; a white rim runs round during this hand's carry.
 */
internal class HandDiscView(parent: Entity, meshes: JcMeshes, private val m: JcMaterials, private val hand: Hand) {
    val root: Entity = Entity().named("handDisc_$hand")
    private val face: Entity
    private val halo: Entity
    private val flashDisc: Entity
    private val rimFrame = Entity()
    private val rim: Rim
    private var faceMat: Material? = null

    init {
        parent.addChild(root)
        face = model(meshes.plate, m.handBase[hand] ?: (if (hand == Hand.RIGHT) m.glowR else m.glowL), "handFace_$hand")
        face.setLocal(scale = Vec3(0.14f, 0.14f, 1f))
        root.addChild(face)
        halo = model(meshes.ring, if (hand == Hand.RIGHT) m.glowR else m.glowL, "handHalo_$hand")
        halo.setLocal(position = Vec3(0f, 0f, -0.001f), rotation = PLATE, scale = Vec3(0.0875f, 1f, 0.0875f))
        root.addChild(halo)
        flashDisc = model(meshes.plate, m.flash, "handFlash_$hand")
        flashDisc.setLocal(position = Vec3(0f, 0f, 0.002f), scale = Vec3(0.14f, 0.14f, 1f))
        root.addChild(flashDisc)
        rimFrame.setLocal(position = Vec3(0f, 0f, 0.003f), rotation = PLATE, scale = Vec3(0.074f, 1f, 0.074f))
        root.addChild(rimFrame)
        rim = Rim(rimFrame, meshes, m.rim, "handRim_$hand")
        root.enabled = false
    }

    fun show(at: Vec3, facing: Quat4, carry: Float?, soon: Boolean, caught: Boolean, test: Boolean) {
        root.show(true)
        root.setLocal(position = at, rotation = facing)
        val want = if (test) m.testF else if (soon) m.handLit[hand] else m.handBase[hand]
        if (want != null && want !== faceMat) {
            faceMat = want
            face.setMaterial(want)
        }
        halo.show(soon)
        flashDisc.show(caught)
        rim.fill(carry ?: 0f)
    }

    fun hide() = root.show(false)

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/** A ghost scarf (design `entities3d.ghostScarves`): outer feather 30 % and a 45 % core, billboarded about the vertical. */
internal class ScarfView(parent: Entity, meshes: JcMeshes, private val m: JcMaterials, index: Int) {
    val root: Entity = Entity().named("scarf_$index")
    private val outer = model(meshes.feather, m.scarfOuter[0], "scarfOuter_$index")
    private val core = model(meshes.feather, m.scarfCore[0], "scarfCore_$index")
    private var color = -1

    init {
        parent.addChild(root)
        root.addChild(outer)
        root.addChild(core)
        outer.setLocal(scale = Vec3(W, H, 1f))
        core.setLocal(position = Vec3(0f, 0.01f, 0.001f), scale = Vec3(W * 0.62f, H * 0.8f, 1f))
        root.enabled = false
    }

    fun show(at: Vec3, yaw: Float, scale: Float, colorSlot: Int, opacity: Float) {
        root.show(true)
        if (colorSlot != color) {
            color = colorSlot
            outer.setMaterial(m.scarfOuter[colorSlot])
            core.setMaterial(m.scarfCore[colorSlot])
        }
        // the hand holds the top corner: the scarf hangs below the point
        root.setLocal(position = at, rotation = Quat4.axisAngle(Vec3.UP, yaw), scale = Vec3(scale, scale, scale))
        root.setOpacity(opacity)
    }

    fun hide() = root.show(false)

    fun destroy() {
        runCatching { root.destroy(true) }
    }

    companion object {
        const val W = 0.16f
        const val H = 0.28f
    }
}

/**
 * Dwell disc Ø 12 cm (design `entities3d.dwellDiscs`): charcoal face with a white icon, an interaction-yellow rim that
 * fills from 0.3 s to the dwell time; a tap of the system pointer also counts.
 */
internal class DwellDiscView(parent: Entity, meshes: JcMeshes, private val m: JcMaterials, val target: String) {
    val root: Entity = Entity().named("dwell_$target")
    private val face: Entity
    private val rimFrame = Entity()
    private val rim: Rim
    val grab: Entity = Entity().named("grab_$target")
    private var faceMat: Material? = null

    init {
        parent.addChild(root)
        face = model(meshes.plate, m.icons[Textures.Icon.PLAY] ?: m.gaze, "dwellFace_$target")
        face.setLocal(scale = Vec3(0.12f, 0.12f, 1f))
        root.addChild(face)
        rimFrame.setLocal(position = Vec3(0f, 0f, 0.002f), rotation = PLATE, scale = Vec3(0.065f, 1f, 0.065f))
        root.addChild(rimFrame)
        rim = Rim(rimFrame, meshes, m.progress, "dwellRim_$target")
        grab.tapBox(Vec3(0.14f, 0.14f, 0.03f))
        root.addChild(grab)
        root.enabled = false
    }

    fun show(at: Vec3, yaw: Float, icon: Textures.Icon, progress: Float, test: Boolean) {
        root.show(true)
        root.setLocal(position = at, rotation = Quat4.axisAngle(Vec3.UP, yaw))
        val want = if (test) m.testF else m.icons[icon]
        if (want != null && want !== faceMat) {
            faceMat = want
            face.setMaterial(want)
        }
        rim.fill(progress)
    }

    fun hide() = root.show(false)

    val shown: Boolean
        get() = root.enabled

    fun isGrab(e: Entity): Boolean = root.enabled && (e.id == grab.id || e.id == face.id)

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/** Gaze dot Ø 2 cm (design `entities3d.gazeDot`): white 60 %, amber while the head stands in for the eyes for a moment. */
internal class GazeDotView(parent: Entity, meshes: JcMeshes, private val m: JcMaterials) {
    val root: Entity = model(meshes.plate, m.gaze, "gazeDot")
    private var amber = false

    init {
        parent.addChild(root)
        root.enabled = false
    }

    fun show(at: Vec3, yaw: Float, amber: Boolean, opacity: Float) {
        root.show(true)
        if (amber != this.amber) {
            this.amber = amber
            root.setMaterial(if (amber) m.gazeAmber else m.gaze)
        }
        root.setLocal(position = at, rotation = Quat4.axisAngle(Vec3.UP, yaw), scale = Vec3(0.02f, 0.02f, 1f))
        root.setOpacity(opacity)
    }

    fun hide() = root.show(false)

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/** Alignment reticle (S03): white ring Ø 8 cm whose outer rim fills in the 1.0 s sampling window. */
internal class ReticleView(parent: Entity, meshes: JcMeshes, m: JcMaterials) {
    val root: Entity = Entity().named("reticle")
    private val plate = Entity()
    private val rim: Rim

    init {
        parent.addChild(root)
        root.addChild(plate)
        plate.setLocal(rotation = PLATE)
        plate.addChild(model(meshes.ring, m.reticle, "reticle_ring").also { it.setLocal(scale = Vec3(0.04f, 1f, 0.04f)) })
        val rimFrame = Entity()
        rimFrame.setLocal(position = Vec3(0f, 0.001f, 0f), scale = Vec3(0.052f, 1f, 0.052f))
        plate.addChild(rimFrame)
        rim = Rim(rimFrame, meshes, m.reticle, "reticle_rim")
        root.enabled = false
    }

    fun show(at: Vec3, facing: Quat4, progress: Float) {
        root.show(true)
        root.setLocal(position = at, rotation = facing)
        rim.fill(progress)
    }

    fun hide() = root.show(false)

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/** Count-in digit plate 8 cm (design `entities3d.countInDigit`): over the hand disc of the beat, 70 %, billboarded. */
internal class DigitView(parent: Entity, meshes: JcMeshes, private val m: JcMaterials) {
    val root: Entity = model(meshes.plate, m.ring, "countInDigit")
    private var digit = -1
    private var test = false

    init {
        parent.addChild(root)
        root.enabled = false
    }

    fun show(at: Vec3, yaw: Float, d: Int, pop: Float, test: Boolean) {
        root.show(true)
        if (d != digit || test != this.test) {
            digit = d
            this.test = test
            (if (test) m.testF else m.digits[d])?.let { root.setMaterial(it) }
        }
        val k = 0.08f * pop
        root.setLocal(position = at, rotation = Quat4.axisAngle(Vec3.UP, yaw), scale = Vec3(k, k, 1f))
        root.setOpacity(0.7f)
    }

    fun hide() = root.show(false)

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}
