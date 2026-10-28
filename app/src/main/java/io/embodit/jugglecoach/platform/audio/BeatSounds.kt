package io.embodit.jugglecoach.platform.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The three short sounds (design `audio.source`), rendered once in memory at [rate] (no assets; the JVM test writes
 * them to WAV for listening and level checks):
 * - [da]: right hand, wood-block tone at 660 Hz (fundamental + a quiet 2.76x partial), 20 ms attack, ~80 ms decay;
 * - [dong]: left hand, the same timbre at 440 Hz;
 * - [pu]: catch, band-limited noise 200 - 800 Hz, 60 ms, 6 dB below the tones.
 * Peaks sit at -12 dBFS (design: default volume restrained). Mono samples, -1..1.
 */
class BeatSounds(private val rate: Int) {
    val da: FloatArray = tone(660f)
    val dong: FloatArray = tone(440f)
    val pu: FloatArray = noise()

    private fun tone(f: Float): FloatArray {
        val n = (TONE_S * rate).toInt()
        val out = FloatArray(n)
        val attack = ATTACK_S * rate
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            val env = if (i < attack) i / attack else exp(-(t - ATTACK_S) / DECAY_TAU_S)
            val v = sin(2 * PI * f * t).toFloat() + 0.25f * sin(2 * PI * f * 2.76 * t).toFloat() * exp(-t / 0.02f)
            out[i] = v * env
        }
        return normalize(out, PEAK)
    }

    private fun noise(): FloatArray {
        val n = (NOISE_S * rate).toInt()
        val out = FloatArray(n)
        var seed = 0x2545F491L
        // one-pole high-pass at 200 Hz and low-pass at 800 Hz
        val hpA = exp(-2.0 * PI * 200.0 / rate).toFloat()
        val lpA = exp(-2.0 * PI * 800.0 / rate).toFloat()
        var hpPrevIn = 0f
        var hpPrevOut = 0f
        var lp = 0f
        var lp2 = 0f
        for (i in 0 until n) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val white = ((seed ushr 33).toInt() and 0xFFFF) / 32768f - 1f
            val hp = hpA * (hpPrevOut + white - hpPrevIn)
            hpPrevIn = white
            hpPrevOut = hp
            lp = lp * lpA + hp * (1f - lpA)
            lp2 = lp2 * lpA + lp * (1f - lpA)
            val t = i.toFloat() / rate
            val env = if (t < 0.005f) t / 0.005f else exp(-(t - 0.005f) / 0.018f)
            out[i] = lp2 * env
        }
        return normalize(out, PEAK * 0.5f)
    }

    private fun normalize(a: FloatArray, peak: Float): FloatArray {
        var m = 0f
        for (v in a) m = maxOf(m, kotlin.math.abs(v))
        if (m > 0f) for (i in a.indices) a[i] = a[i] / m * peak
        // 2 ms fade-out so the cut never clicks
        val fade = (0.002f * rate).toInt().coerceAtMost(a.size)
        for (i in 0 until fade) a[a.size - 1 - i] *= i.toFloat() / fade
        return a
    }

    companion object {
        /** -12 dBFS. */
        const val PEAK = 0.251f
        private const val TONE_S = 0.12f
        private const val NOISE_S = 0.06f
        private const val ATTACK_S = 0.02f
        private const val DECAY_TAU_S = 0.025f
    }
}
