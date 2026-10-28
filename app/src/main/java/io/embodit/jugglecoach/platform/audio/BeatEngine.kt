package io.embodit.jugglecoach.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import io.embodit.jugglecoach.domain.juggle.Hit
import io.embodit.jugglecoach.domain.juggle.Lesson
import io.embodit.jugglecoach.domain.model.Hand
import io.embodit.jugglecoach.platform.JcLog
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * The beat and the lesson clock (design `audio`, the engine of DanceSteps): one long-lived streaming `AudioTrack`
 * (48 kHz, stereo, 16-bit) that a thread fills in 10 ms blocks. The three sounds ("da" right 660 Hz, "dong" left
 * 440 Hz, "pu" catch noise) are rendered once at start ([BeatSounds]) and mixed at their hit frames; right-hand
 * sounds are 6 dB louder on the right channel, left-hand ones on the left.
 *
 * The clock: [arm] fixes lesson time [Run.startAt] at a write-frame a little ahead of the write head. The frame being
 * played is `getTimestamp` extrapolated (refreshed every 500 ms; fallback: the playback head minus 80 ms; no track at
 * all: the wall clock), so [lessonTime] is where the listener is, and the scene drives the scarves from it: sound
 * and picture share one clock. Muted = zeros are written, the stream and the clock keep running.
 */
class BeatEngine {
    /**
     * One armed run. Lesson time = [startAt] + seconds since the anchor x [speed]. [countIn] adds the four count-in
     * clicks before lesson time 0; [endAt] stops new hits (demo end / set end).
     */
    class Run(val lesson: Lesson, val startAt: Float, val speed: Float, val countIn: Boolean, val endAt: Float) {
        @Volatile var anchorFrame = 0L
        @Volatile var anchorMs = 0L
    }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var run: Run? = null
    @Volatile var muted = false
    private val writeFrame = AtomicLong(0L)
    private val ts = AudioTimestamp()
    private var tsOk = false
    private var tsAtMs = 0L
    private val sounds = BeatSounds(RATE)

    val armed: Boolean
        get() = run != null

    /** Opens the stream and starts the fill thread. Main thread; idempotent. */
    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf, BLOCK * 2 * 2 * 6)
        val t =
            runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            }
                .onFailure { JcLog.e("AUDIO track build failed: $it", it) }
                .getOrNull() ?: return
        track = t
        writeFrame.set(0L)
        tsOk = false
        runCatching { t.play() }.onFailure { JcLog.e("AUDIO play failed: $it", it) }
        running = true
        thread =
            Thread({ fillLoop(t) }, "juggle-audio").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
        JcLog.i("AUDIO start buffer=$bufBytes min=$minBuf")
    }

    /** Pauses and flushes (background); [start] opens a fresh stream afterwards. The run is dropped. */
    fun stop() {
        val t = track
        running = false
        run = null
        runCatching { thread?.join(500) }
        thread = null
        if (t != null) {
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
        JcLog.i("AUDIO stop")
    }

    /** Arms [r]: its lesson time [Run.startAt] sounds [LEAD_MS] from now. Replaces any run. */
    fun arm(r: Run) {
        r.anchorFrame = writeFrame.get() + RATE * LEAD_MS / 1000
        r.anchorMs = SystemClock.elapsedRealtime() + LEAD_MS
        run = r
        JcLog.i("AUDIO arm course=${r.lesson.course.number} start=${JcLog.f(r.startAt)} speed=${r.speed} countIn=${r.countIn} end=${JcLog.f(r.endAt)} anchor=${r.anchorFrame} track=${track != null}")
    }

    /** Back to silence (the stream keeps running). */
    fun silence() {
        if (run != null) JcLog.i("AUDIO silence")
        run = null
    }

    /** Lesson time as the listener hears it, or null when nothing is armed. */
    fun lessonTime(): Float? {
        val r = run ?: return null
        val sec =
            playFrame()?.let { (it - r.anchorFrame) / RATE }
                ?: ((SystemClock.elapsedRealtime() - r.anchorMs) / 1000.0)
        return (r.startAt + sec * r.speed).toFloat()
    }

    private fun playFrame(): Double? {
        val t = track ?: return null
        if (!running) return null
        val now = SystemClock.elapsedRealtime()
        if (now - tsAtMs > TS_REFRESH_MS) {
            tsAtMs = now
            tsOk = runCatching { t.getTimestamp(ts) }.getOrDefault(false)
        }
        if (tsOk) {
            val dt = (java.lang.System.nanoTime() - ts.nanoTime) / 1e9
            return ts.framePosition + dt * RATE
        }
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrNull() ?: return null
        return head.toDouble() - RATE * FALLBACK_LATENCY_MS / 1000.0
    }

    // =========================================================================================
    // the audio thread
    // =========================================================================================

    private class Voice(val samples: FloatArray, val start: Long, val gainL: Float, val gainR: Float)

    private fun fillLoop(t: AudioTrack) {
        val buf = ShortArray(BLOCK * 2)
        val left = FloatArray(BLOCK)
        val right = FloatArray(BLOCK)
        val voices = ArrayList<Voice>()
        var last: Run? = null
        var frame = 0L
        var lastHit = Float.NEGATIVE_INFINITY
        while (running) {
            val r = run
            if (r !== last) {
                voices.clear()
                last = r
                lastHit = Float.NEGATIVE_INFINITY
            }
            java.util.Arrays.fill(left, 0f)
            java.util.Arrays.fill(right, 0f)
            if (r != null) {
                // lesson-time window of this block
                val fromT = r.startAt + ((frame - r.anchorFrame).toFloat() / RATE) * r.speed
                val toT = r.startAt + ((frame + BLOCK - r.anchorFrame).toFloat() / RATE) * r.speed
                if (toT > lastHit) {
                    val from = max(fromT, lastHit)
                    for (h in hits(r, from, toT)) voices += voiceFor(r, h)
                    lastHit = toT
                }
                if (!muted) mix(voices, frame, left, right) else dropFinished(voices, frame)
            }
            for (i in 0 until BLOCK) {
                buf[2 * i] = (left[i].coerceIn(-1f, 1f) * 32000f).toInt().toShort()
                buf[2 * i + 1] = (right[i].coerceIn(-1f, 1f) * 32000f).toInt().toShort()
            }
            val written = runCatching { t.write(buf, 0, buf.size) }.getOrDefault(-1)
            if (written < 0) {
                JcLog.w("AUDIO write failed $written")
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    return
                }
            }
            frame += BLOCK
            writeFrame.set(frame)
        }
    }

    /** Count-in clicks (4 x 0.8 s before lesson time 0: R high, L low, ...) and the lesson's throws and catches. */
    private fun hits(r: Run, from: Float, to: Float): List<Hit> {
        val out = ArrayList<Hit>()
        if (r.countIn) {
            for (i in 0 until Lesson.COUNT_IN_BEATS) {
                val at = -Lesson.COUNT_IN_TOTAL + i * Lesson.COUNT_IN_S
                if (at >= from && at < to) out += Hit(at, Hit.Kind.THROW, r.lesson.countInHand(i), 0.85f)
            }
        }
        if (to > 0f || !r.countIn) {
            for (h in r.lesson.hitsBetween(from, min(to, r.endAt))) out += h
        }
        return out
    }

    private fun voiceFor(r: Run, h: Hit): Voice {
        val sec = (h.t - r.startAt) / r.speed
        val start = r.anchorFrame + (sec * RATE).toLong()
        val samples =
            when (h.kind) {
                Hit.Kind.CATCH -> sounds.pu
                Hit.Kind.THROW -> if (h.hand == Hand.RIGHT) sounds.da else sounds.dong
            }
        val near = h.gain
        val far = h.gain * 0.5f
        return if (h.hand == Hand.RIGHT) Voice(samples, start, far, near) else Voice(samples, start, near, far)
    }

    private fun mix(voices: MutableList<Voice>, frame: Long, left: FloatArray, right: FloatArray) {
        var i = voices.size - 1
        while (i >= 0) {
            val v = voices[i]
            val end = v.start + v.samples.size
            if (end <= frame) {
                voices.removeAt(i)
                i--
                continue
            }
            val from = max(0L, v.start - frame).toInt()
            val to = min(BLOCK.toLong(), end - frame).toInt()
            for (j in from until to) {
                val s = v.samples[(frame + j - v.start).toInt()]
                left[j] += s * v.gainL
                right[j] += s * v.gainR
            }
            i--
        }
    }

    private fun dropFinished(voices: MutableList<Voice>, frame: Long) {
        voices.removeAll { it.start + it.samples.size <= frame }
    }

    companion object {
        const val RATE = 48_000
        private const val BLOCK = 480
        private const val LEAD_MS = 120L
        private const val TS_REFRESH_MS = 500L
        private const val FALLBACK_LATENCY_MS = 80L
    }
}
