package io.embodit.jugglecoach.domain

import io.embodit.jugglecoach.domain.juggle.BodyFrame
import io.embodit.jugglecoach.domain.juggle.Course
import io.embodit.jugglecoach.domain.juggle.Lesson
import io.embodit.jugglecoach.domain.model.BodyParams
import io.embodit.jugglecoach.domain.model.Hand
import io.embodit.jugglecoach.domain.model.Vec3
import io.embodit.jugglecoach.platform.audio.BeatSounds
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The design's numbers (`verification.emulatorCanVerify` / `rules.geometry.timing`) checked on the domain. */
class LessonTest {
    private val d = BodyParams()

    @Test
    fun defaultTimingMatchesTheDesign() {
        val p1 = Lesson(Course.P1, d)
        println("P1 carry=${p1.carry} up=${p1.paths.getValue(Hand.RIGHT).up} F=${p1.flight} period=${p1.unitPeriod}")
        assertEquals(0.31f, p1.carry, 0.01f)
        assertEquals(0.91f, p1.flight, 0.02f)
        assertEquals(1.8f, p1.unitPeriod, 0.05f)
        assertEquals(2.3f, Lesson(Course.P2, d).unitPeriod, 0.06f)
        assertEquals(2.0f, Lesson(Course.P3, d).unitPeriod, 0.08f)
        assertEquals(0.44f, Lesson(Course.P4, d).interval, 0.02f)
        assertEquals(0.70f, Lesson(Course.P5, d).interval, 0.03f)
        assertEquals(0.45f, Lesson(Course.P6, d).interval, 0.02f)
        // peak +0.40: F = 1.20, I = 0.53 (design P4 note)
        val high = Lesson(Course.P4, d.copy(peakRise = 0.40f))
        assertEquals(1.20f, high.flight, 0.02f)
        assertEquals(0.53f, high.interval, 0.02f)
    }

    @Test
    fun defaultPointsMatchTheDesign() {
        val r = Lesson(Course.P1, d).paths.getValue(Hand.RIGHT)
        assertEquals(0.20f, r.hold.x, 1e-3f)
        assertEquals(-0.35f, r.hold.y, 1e-3f)
        assertEquals(0.048f, r.release.x, 1e-3f) // 0.12 S (design rounds to 0.05)
        assertEquals(-0.16f, r.peak.x, 1e-3f)
        assertEquals(0.20f, r.peak.y, 1e-3f)
        assertEquals(-0.20f, r.catch.x, 1e-3f)
        assertEquals(0.30f, Lesson(Course.P5, d).hold(Hand.RIGHT).x, 1e-3f)
        assertEquals(0.08f, Lesson(Course.P6, d).hold(Hand.RIGHT).x, 1e-3f)
        assertEquals(-0.08f, Lesson(Course.P6, d).paths.getValue(Hand.RIGHT).catch.x, 1e-3f)
        // shoulder 0.32 / 0.50 scale the hands
        assertEquals(0.16f, Lesson(Course.P1, d.copy(shoulder = 0.32f)).hold(Hand.RIGHT).x, 1e-3f)
        assertEquals(0.25f, Lesson(Course.P1, d.copy(shoulder = 0.50f)).hold(Hand.RIGHT).x, 1e-3f)
    }

    @Test
    fun phaseHalfIsAtThePeakAndCascadeThirdIsTopRisingHand() {
        val p1 = Lesson(Course.P1, d)
        val t = p1.phaseStart + 0.5f * p1.phaseLength
        val s = p1.scarvesAt(t)[0]
        assertTrue("P1 phase 0.5 near the peak: ${s.at}", s.at.distanceTo(p1.paths.getValue(Hand.RIGHT).peak) < 0.04f)
        val p4 = Lesson(Course.P4, d)
        val all = p4.scarvesAt(p4.phaseStart + 0.33f * p4.phaseLength)
        println("P4 0.33: $all")
        assertTrue(all[0].flying && all[0].at.y > 0.0f) // orange just past the top
        assertTrue(all[2].hand == Hand.RIGHT || !all[2].flying) // yellow still in the right hand
        // P1 0.9: about to land on the left disc
        val c = p1.cueAt(Hand.LEFT, p1.phaseStart + 0.9f * p1.phaseLength)
        assertTrue(c.catchSoon)
    }

    @Test
    fun countsAndSetEnd() {
        val p2 = Lesson(Course.P2, d)
        assertEquals(1, p2.countAt(0f))
        assertEquals(1, p2.countAt(p2.unitPeriod - 0.01f))
        assertEquals(2, p2.countAt(p2.unitPeriod + 0.01f))
        val p4 = Lesson(Course.P4, d)
        assertEquals(10, p4.countAt(9 * p4.interval + 0.01f))
        assertTrue(p4.unitDoneAt(10) > 9 * p4.interval + p4.flight - 0.01f)
    }

    @Test
    fun bodyFrameAxes() {
        val f = BodyFrame(Vec3(0f, 1.6f, 0f), 0f)
        val p = f.toStage(0.2f, -0.35f, 0.4f)
        assertEquals(0.2f, p.x, 1e-4f)
        assertEquals(1.25f, p.y, 1e-4f)
        assertEquals(-0.4f, p.z, 1e-4f)
        val turned = BodyFrame(Vec3.ZERO, (Math.PI / 2).toFloat()) // facing -X
        assertEquals(-1f, turned.toStage(0f, 0f, 1f).x, 1e-4f)
        assertEquals(-1f, turned.toStage(1f, 0f, 0f).z, 1e-4f)
    }

    /** Writes the three sounds to build/sounds/ (48 kHz mono 16-bit) for listening; checks the peaks. */
    @Test
    fun renderSounds() {
        val s = BeatSounds(48_000)
        val dir = File("build/sounds").apply { mkdirs() }
        for ((name, a) in listOf("da" to s.da, "dong" to s.dong, "pu" to s.pu)) {
            val peak = a.maxOf { kotlin.math.abs(it) }
            println("SOUND $name n=${a.size} ms=${a.size / 48} peak=${"%.3f".format(peak)} dBFS=${"%.1f".format(20 * kotlin.math.log10(peak))}")
            assertTrue(peak <= BeatSounds.PEAK + 1e-3f)
            wav(File(dir, "$name.wav"), a)
        }
    }

    private fun wav(f: File, a: FloatArray) {
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            fun i32(v: Int) = r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = r.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            r.writeBytes("RIFF"); i32(36 + a.size * 2); r.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1); i32(48_000); i32(96_000); i16(2); i16(16)
            r.writeBytes("data"); i32(a.size * 2)
            for (v in a) i16((v * 32767f).toInt())
        }
    }
}
