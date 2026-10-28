package io.embodit.jugglecoach.domain.model

/** Why the app cannot run right now (S11). */
enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED, NO_HEAD_POSE }

enum class LanguagePref { SYSTEM, ZH, EN }

/** Scarf speed segment on the panel (design `rules.geometry.vt`): 0.7 / 0.9 / 1.1 m/s. */
enum class ScarfSpeed(val vt: Float) {
    FLOATY(0.7f),
    NORMAL(0.9f),
    FAST(1.1f);

    companion object {
        /** The segment that matches [vt] exactly (the menu fine-tune may sit between them: null). */
        fun of(vt: Float): ScarfSpeed? = entries.firstOrNull { kotlin.math.abs(it.vt - vt) < 0.001f }
    }
}

/** Throws per set (design `rules.setCounting`); INF = until paused. */
enum class SetSize(val n: Int?) { SIX(6), TEN(10), TWENTY(20), INF(null) }

/** "Watch once" slow motion (S04 only; the follow-along is always 1:1). */
enum class DemoSlow(val k: Float) { X03(0.3f), X05(0.5f), X1(1f) }

/** Dwell time (design `input.dwell.whyNot1.5s`). */
enum class DwellTime(val seconds: Float) { S10(1.0f), S12(1.2f), S15(1.5f) }

enum class Hand(val sign: Float) {
    RIGHT(1f),
    LEFT(-1f);

    val other: Hand
        get() = if (this == RIGHT) LEFT else RIGHT
}

/**
 * The body-scale settings that shape the path (design `rules.geometry.params`), metres. All are kept in
 * [SettingsStore]; the menu steps them inside the design ranges.
 */
data class BodyParams(
    val shoulder: Float = 0.40f,
    val handDrop: Float = 0.35f,
    val zFront: Float = 0.40f,
    val peakRise: Float = 0.20f,
    val vt: Float = 0.9f,
) {
    fun clamped(): BodyParams =
        BodyParams(
            shoulder = shoulder.coerceIn(SHOULDER_MIN, SHOULDER_MAX),
            handDrop = handDrop.coerceIn(HAND_DROP_MIN, HAND_DROP_MAX),
            zFront = zFront.coerceIn(Z_FRONT_MIN, Z_FRONT_MAX),
            peakRise = peakRise.coerceIn(PEAK_MIN, PEAK_MAX),
            vt = vt.coerceIn(VT_MIN, VT_MAX),
        )

    fun lerp(o: BodyParams, t: Float): BodyParams =
        BodyParams(
            shoulder + (o.shoulder - shoulder) * t,
            handDrop + (o.handDrop - handDrop) * t,
            zFront + (o.zFront - zFront) * t,
            peakRise + (o.peakRise - peakRise) * t,
            vt + (o.vt - vt) * t,
        )

    companion object {
        const val SHOULDER_MIN = 0.32f
        const val SHOULDER_MAX = 0.50f
        const val SHOULDER_STEP = 0.02f
        const val HAND_DROP_MIN = 0.25f
        const val HAND_DROP_MAX = 0.50f
        const val HAND_DROP_STEP = 0.05f
        const val Z_FRONT_MIN = 0.30f
        const val Z_FRONT_MAX = 0.55f
        const val Z_FRONT_STEP = 0.05f
        const val PEAK_MIN = -0.10f
        const val PEAK_MAX = 0.40f
        const val PEAK_STEP = 0.05f
        const val VT_MIN = 0.5f
        const val VT_MAX = 1.3f
        const val VT_STEP = 0.1f

        /** Release point below the eyes (fixed, not shown to the user). */
        const val RELEASE_DROP = 0.15f

        /** Hand carry speed (fixed). */
        const val V_CARRY = 0.8f
    }
}

/** Everything the user can set (design `persistence.what`). */
data class Settings(
    val body: BodyParams = BodyParams(),
    val demoSlow: DemoSlow = DemoSlow.X05,
    val setSize: SetSize = SetSize.TEN,
    val muted: Boolean = false,
    val dwell: DwellTime = DwellTime.S12,
    val autoDemo: Boolean = true,
    val language: LanguagePref = LanguagePref.SYSTEM,
)
