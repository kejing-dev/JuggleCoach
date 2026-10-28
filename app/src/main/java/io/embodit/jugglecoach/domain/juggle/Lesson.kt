package io.embodit.jugglecoach.domain.juggle

import io.embodit.jugglecoach.domain.model.BodyParams
import io.embodit.jugglecoach.domain.model.Hand
import io.embodit.jugglecoach.domain.model.Quat4
import io.embodit.jugglecoach.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in the path plane of the body frame: x right (user's view), y up from the eyes, metres. */
data class P2(val x: Float, val y: Float) {
    fun lerp(o: P2, t: Float) = P2(x + (o.x - x) * t, y + (o.y - y) * t)

    fun distanceTo(o: P2): Float = hypot(o.x - x, o.y - y)
}

/**
 * Body frame F (design `rules.bodyFrame`): the head pose at alignment. Origin = head position, [yaw] = the head's
 * horizontal heading (pitch and roll ignored). Body coordinates: x right, y up, z ahead (towards where the user faces).
 */
data class BodyFrame(val origin: Vec3, val yaw: Float) {
    /** Unit vector ahead (Stage). yaw 0 looks along -Z; positive yaw turns left. */
    val ahead: Vec3
        get() = Vec3(-sin(yaw), 0f, -cos(yaw))

    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    /** Rotation whose -Z is [ahead] (a panel or plate with this rotation faces back towards the user). */
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)

    fun toStage(x: Float, y: Float, z: Float): Vec3 = origin + right * x + Vec3.UP * y + ahead * z

    fun toStage(p: P2, z: Float): Vec3 = toStage(p.x, p.y, z)

    /** Stage point -> body coordinates (x, y, z). */
    fun toBody(p: Vec3): Vec3 {
        val d = p - origin
        return Vec3(d.dot(right), d.y, d.dot(ahead))
    }
}

/**
 * One throw path (design `rules.geometry.pathShape`): a hand carry (hold -> release, straight, [carry] s at 0.8 m/s),
 * the rise (release -> peak: x linear, y decelerating to zero at the peak, [up] s) and the fall (peak -> catch: y at
 * the terminal speed, x drifting linearly to the catch, [down] s).
 */
class ThrowPath(val hold: P2, val release: P2, val peak: P2, val catch: P2, vt: Float) {
    val carry: Float = hold.distanceTo(release) / BodyParams.V_CARRY
    val up: Float = 1.1f * sqrt(2f * (peak.y - release.y).coerceAtLeast(0.01f) / G)
    val down: Float = (peak.y - catch.y).coerceAtLeast(0.01f) / vt
    val flight: Float = up + down

    fun carryAt(s: Float): P2 = hold.lerp(release, s.coerceIn(0f, 1f))

    /** Position [t] seconds after release (0..flight). */
    fun flightAt(t: Float): P2 {
        if (t <= up) {
            val s = (t / up).coerceIn(0f, 1f)
            val y = release.y + (peak.y - release.y) * (1f - (1f - s) * (1f - s))
            return P2(release.x + (peak.x - release.x) * s, y)
        }
        val s = ((t - up) / down).coerceIn(0f, 1f)
        return P2(peak.x + (catch.x - peak.x) * s, peak.y + (catch.y - peak.y) * s)
    }

    /** [n] + 1 points of the flight (release .. catch) for drawing. */
    fun flightPoints(n: Int = 48): List<P2> = (0..n).map { flightAt(flight * it / n) }

    private companion object {
        const val G = 9.81f
    }
}

/** One throw of the lesson's timeline. [t] = release time (s) on the lesson clock; 0 = the first release. */
data class Throw(val k: Int, val t: Float, val hand: Hand, val scarf: Int, val catchHand: Hand, val unit: Int, val path: ThrowPath) {
    val carryStart: Float
        get() = t - path.carry

    val peakAt: Float
        get() = t + path.up

    val catchAt: Float
        get() = t + path.flight
}

/** Where a scarf is at one moment. [hand] is set while it rests in a hand. */
data class ScarfState(val scarf: Int, val at: P2, val dz: Float, val scale: Float, val hand: Hand?, val flying: Boolean, val swayPhase: Float)

/** Cues of one hand at one moment (design `entities3d.handDiscs` / `arcTubes` / `peakRings`). */
data class HandCue(
    /** 0..1 how far the hand's carry has gone (the disc rim runs round); null outside a carry. */
    val carry: Float?,
    /** A scarf lands on this hand within 0.3 s. */
    val catchSoon: Boolean,
    /** A scarf landed on this hand within the last 0.1 s. */
    val caught: Boolean,
    /** This hand's path is highlighted (carry .. peak of a throw from this hand). */
    val pathLit: Boolean,
    /** Scale of the peak ring of this hand's path (1, up to 1.3 while a scarf passes the peak). */
    val ringScale: Float,
)

/** Sound events on the lesson clock. */
data class Hit(val t: Float, val kind: Kind, val hand: Hand, val gain: Float) {
    enum class Kind { THROW, CATCH }
}

/**
 * One lesson at one set of body parameters: the paths, the timeline of throws (generated on demand, endless) and the
 * per-moment states the scene draws. Pure; the scene feeds it the lesson clock.
 */
class Lesson(val course: Course, val params: BodyParams) {
    private val s = params.shoulder
    private val hd = -params.handDrop
    private val rel = -BodyParams.RELEASE_DROP
    private val pk = params.peakRise

    /** Path used by throws from each hand (only RIGHT for P5). */
    val paths: Map<Hand, ThrowPath> = Hand.entries.filter { it in course.hands }.associateWith { pathOf(it) }

    private val right = paths.getValue(Hand.RIGHT)
    val carry: Float = right.carry
    val flight: Float = right.flight

    /** Hold (= the hand disc) of each hand in use. */
    fun hold(hand: Hand): P2 = (paths[hand] ?: pathOf(hand)).hold

    /** Hold time + carry for the continuous patterns. */
    private val dwellInHand = HOLD_S + carry

    /** Throw interval of the continuous patterns (P4 / P6: 3 scarves, P5: 2 in one hand). */
    val interval: Float =
        when (course) {
            Course.P4, Course.P6 -> (flight + dwellInHand) / 3f
            Course.P5 -> (flight + dwellInHand) / 2f
            else -> 0f
        }

    /** Period of one counted unit (single throw / pair) for the paced patterns. */
    val unitPeriod: Float =
        when (course) {
            Course.P1 -> carry + flight + 0.6f
            Course.P2 -> carry + 1.35f * flight + 0.8f
            Course.P3 -> carry + flight + 0.8f
            else -> interval
        }

    /** Debug `phase=` window (design qaCapture: P1 0.5 = at the peak, 0.9 = about to land; P4 0.33 = top / rising / in hand). */
    val phaseStart: Float = if (course.continuous) 0f else -carry
    val phaseLength: Float =
        when (course) {
            Course.P1, Course.P3 -> carry + flight
            Course.P2 -> carry + 1.35f * flight
            Course.P4, Course.P6 -> 3f * interval
            Course.P5 -> 2f * interval
        }

    private fun pathOf(hand: Hand): ThrowPath {
        val m = hand.sign
        val (h, r, p, c) =
            when (course) {
                Course.P1, Course.P2, Course.P4 -> listOf(0.5f, 0.12f, -0.40f, -0.5f)
                Course.P3 -> listOf(0.5f, 0.5f, 0.5f, 0.5f)
                Course.P5 -> listOf(0.75f, 0.125f, 0.6f, 0.75f)
                Course.P6 -> listOf(0.2f, 0.7f, -0.15f, -0.2f)
            }
        return ThrowPath(P2(m * h * s, hd), P2(m * r * s, rel), P2(m * p * s, pk), P2(m * c * s, hd), params.vt)
    }

    // ---------------------------------------------------------------------------------------------
    // timeline
    // ---------------------------------------------------------------------------------------------

    private val throws = ArrayList<Throw>()

    fun throwAt(k: Int): Throw {
        while (throws.size <= k) throws += build(throws.size)
        return throws[k]
    }

    private fun build(k: Int): Throw =
        when (course) {
            Course.P1 -> {
                val hand = if (k % 2 == 0) Hand.RIGHT else Hand.LEFT
                Throw(k, k * unitPeriod, hand, 0, hand.other, k + 1, paths.getValue(hand))
            }
            Course.P2 -> {
                val p = k / 2
                val j = k % 2
                val first = if (p % 2 == 0) Hand.RIGHT else Hand.LEFT
                val hand = if (j == 0) first else first.other
                Throw(k, p * unitPeriod + j * 0.35f * flight, hand, j, hand.other, p + 1, paths.getValue(hand))
            }
            Course.P3 -> {
                val p = k / 2
                val hand = if (k % 2 == 0) Hand.RIGHT else Hand.LEFT
                Throw(k, p * unitPeriod, hand, k % 2, hand, p + 1, paths.getValue(hand))
            }
            Course.P4, Course.P6 -> {
                val hand = if (k % 2 == 0) Hand.RIGHT else Hand.LEFT
                Throw(k, k * interval, hand, k % 3, hand.other, k + 1, paths.getValue(hand))
            }
            Course.P5 -> Throw(k, k * interval, Hand.RIGHT, k % 2, Hand.RIGHT, k + 1, right)
        }

    /** Index of the first throw released at or after [t] (throws are in time order). */
    private fun indexAt(t: Float): Int {
        if (t <= 0f) return 0
        var hi = 1
        while (hi < MAX_THROWS && throwAt(hi).t < t) hi *= 2
        hi = minOf(hi, MAX_THROWS - 1)
        var lo = 0
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (throwAt(mid).t < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Throws whose release lies in [from, to). */
    fun throwsBetween(from: Float, to: Float): List<Throw> {
        val out = ArrayList<Throw>()
        var k = indexAt(from)
        while (k < MAX_THROWS) {
            val th = throwAt(k)
            if (th.t >= to) break
            if (th.t >= from) out += th
            k++
        }
        return out
    }

    /** Counted units (throws, or pairs for P2 / P3) released by [t]. */
    fun countAt(t: Float): Int {
        if (t < 0f) return 0
        var k = maxOf(0, indexAt(t) - 2)
        var units = 0
        while (k < MAX_THROWS) {
            val th = throwAt(k)
            if (th.t > t) break
            units = th.unit
            k++
        }
        return units
    }

    /** Time when the last scarf of unit [n] has been caught. */
    fun unitDoneAt(n: Int): Float {
        var k = maxOf(0, n - 1)
        while (k > 0 && throwAt(k).unit >= n) k--
        var end = 0f
        while (k < MAX_THROWS) {
            val th = throwAt(k)
            if (th.unit > n) break
            if (th.unit == n) end = maxOf(end, th.catchAt)
            k++
        }
        return end
    }

    /** Units shown by "watch once" (design `rules.demo`: 3 rounds). */
    val demoUnits: Int =
        when (course) {
            Course.P1, Course.P2, Course.P3 -> 3
            Course.P4, Course.P6 -> 6
            Course.P5 -> 4
        }

    /** Sound events whose time lies in [from, to). Throws in P4 / P6 accent every third, P5 every second. */
    fun hitsBetween(from: Float, to: Float): List<Hit> {
        val out = ArrayList<Hit>()
        for (th in throwsBetween(from - 4f, to)) {
            if (th.t >= from) {
                val accent =
                    when (course) {
                        Course.P4, Course.P6 -> th.k % 3 == 0
                        Course.P5 -> th.k % 2 == 0
                        else -> true
                    }
                out += Hit(th.t, Hit.Kind.THROW, th.hand, if (accent) 1f else 0.7f)
            }
            if (th.catchAt >= from && th.catchAt < to) out += Hit(th.catchAt, Hit.Kind.CATCH, th.catchHand, 1f)
        }
        out.sortBy { it.t }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // one moment
    // ---------------------------------------------------------------------------------------------

    private fun lastThrowOf(scarf: Int, t: Float): Throw? {
        var best: Throw? = null
        var k = maxOf(0, indexAt(t) - 8)
        while (k < MAX_THROWS) {
            val th = throwAt(k)
            if (th.t > t) break
            if (th.scarf == scarf) best = th
            k++
        }
        return best
    }

    private fun nextThrowOf(scarf: Int, t: Float): Throw? {
        var k = maxOf(0, indexAt(t) - 1)
        while (k < MAX_THROWS) {
            val th = throwAt(k)
            if (th.t > t && th.scarf == scarf) return th
            k++
        }
        return null
    }

    /**
     * Every scarf at lesson time [t] (design `entities3d.ghostScarves`): flying on a path, carried (0.8 size) along
     * the carry line, or resting in a hand (0.6 size, two in one hand 0.04 m apart, the later one 0.01 m behind).
     * Before the first carry every scarf rests in its starting hand.
     */
    fun scarvesAt(t: Float): List<ScarfState> {
        val resting = HashMap<Hand, MutableList<Int>>()
        val out = arrayOfNulls<ScarfState>(course.scarves)
        for (sc in 0 until course.scarves) {
            val prev = lastThrowOf(sc, t)
            val next = nextThrowOf(sc, t)
            when {
                prev != null && t < prev.catchAt -> {
                    val ft = t - prev.t
                    out[sc] = ScarfState(sc, prev.path.flightAt(ft), 0f, 1f, null, flying = true, swayPhase = if (ft > prev.path.up) ft - prev.path.up else 0f)
                }
                next != null && t >= next.carryStart -> {
                    val q = (t - next.carryStart) / next.path.carry
                    out[sc] = ScarfState(sc, next.path.carryAt(q), 0f, CARRY_SCALE, null, flying = false, swayPhase = 0f)
                }
                else -> {
                    val hand = prev?.catchHand ?: course.startHand(sc)
                    resting.getOrPut(hand) { ArrayList() } += sc
                }
            }
        }
        for ((hand, list) in resting) {
            val base = hold(hand)
            list.sort()
            val n = list.size
            for ((i, sc) in list.withIndex()) {
                val off = (i - (n - 1) / 2f) * STACK_DX
                out[sc] = ScarfState(sc, P2(base.x + off, base.y), i * STACK_DZ, HAND_SCALE, hand, flying = false, swayPhase = 0f)
            }
        }
        return out.map { it!! }
    }

    /** Cues of [hand] at lesson time [t]. */
    fun cueAt(hand: Hand, t: Float): HandCue {
        var carryQ: Float? = null
        var soon = false
        var caught = false
        var lit = false
        var ring = 1f
        for (th in throwsBetween(t - flight - 1f, t + carry + 0.5f)) {
            if (th.hand == hand) {
                if (t >= th.carryStart && t < th.t) carryQ = (t - th.carryStart) / th.path.carry
                if (t >= th.carryStart && t <= th.peakAt) lit = true
                val d = abs(t - th.peakAt)
                if (d < PEAK_PULSE_S) ring = maxOf(ring, 1f + 0.3f * (1f - d / PEAK_PULSE_S))
            }
            if (th.catchHand == hand) {
                val toCatch = th.catchAt - t
                if (toCatch in 0f..CATCH_SOON_S) soon = true
                if (-toCatch in 0f..CATCH_FLASH_S) caught = true
            }
        }
        return HandCue(carryQ, soon, caught, lit, ring)
    }

    /**
     * Count-in beat at count-in time [c] (0 = first beat; beats every 0.8 s; the fifth beat = first release):
     * 0..3 = beats 1..4 (digits 4 3 2 1 over R L R L), null outside.
     */
    fun countInBeat(c: Float): Int? = if (c < 0f || c >= COUNT_IN_BEATS * COUNT_IN_S) null else (c / COUNT_IN_S).toInt()

    /** Hand of count-in beat [i] (R, L, R, L; P5 has only the right hand). */
    fun countInHand(i: Int): Hand = if (course == Course.P5 || i % 2 == 0) Hand.RIGHT else Hand.LEFT

    companion object {
        const val HOLD_S = 0.1f
        const val COUNT_IN_BEATS = 4
        const val COUNT_IN_S = 0.8f
        const val CATCH_SOON_S = 0.3f
        const val CATCH_FLASH_S = 0.1f
        const val PEAK_PULSE_S = 0.1f
        const val HAND_SCALE = 0.6f
        const val CARRY_SCALE = 0.8f
        const val STACK_DX = 0.04f
        const val STACK_DZ = 0.01f
        const val MAX_THROWS = 4000

        /** Count-in length before the first release: 4 beats x 0.8 s. */
        const val COUNT_IN_TOTAL = COUNT_IN_BEATS * COUNT_IN_S
    }
}

/** The six lessons (design `patterns`). */
enum class Course(val number: Int, val scarves: Int, val hands: Set<Hand>, val continuous: Boolean) {
    P1(1, 1, setOf(Hand.RIGHT, Hand.LEFT), false),
    P2(2, 2, setOf(Hand.RIGHT, Hand.LEFT), false),
    P3(3, 2, setOf(Hand.RIGHT, Hand.LEFT), false),
    P4(4, 3, setOf(Hand.RIGHT, Hand.LEFT), true),
    P5(5, 2, setOf(Hand.RIGHT), true),
    P6(6, 3, setOf(Hand.RIGHT, Hand.LEFT), true);

    /** Where each scarf starts: P4 / P6 right hand two (orange, yellow), left one (blue); P5 both right. */
    fun startHand(scarf: Int): Hand =
        when (this) {
            P1 -> Hand.RIGHT
            P2, P3 -> if (scarf == 0) Hand.RIGHT else Hand.LEFT
            P4, P6 -> if (scarf == 1) Hand.LEFT else Hand.RIGHT
            P5 -> Hand.RIGHT
        }

    /** Colour slot of a scarf: 0 orange (scarfCoral), 1 blue (scarfSky), 2 yellow (scarfLemon); P5's second is yellow. */
    fun colorOf(scarf: Int): Int = if (this == P5 && scarf == 1) 2 else scarf

    val next: Course?
        get() = entries.getOrNull(ordinal + 1)

    val previous: Course?
        get() = entries.getOrNull(ordinal - 1)

    companion object {
        fun of(number: Int): Course? = entries.firstOrNull { it.number == number }
    }
}
