package io.embodit.jugglecoach.ui.jc.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.embodit.jugglecoach.domain.model.Hand
import io.embodit.jugglecoach.ui.theme.JcColors

/**
 * Programmatic textures of the 3D plates (no image assets). Every plate is a disc, so the corners of a bitmap never
 * show; the bitmaps carry alpha in case the material honours it. Orientation is checked with [testF] (debug
 * `testtex=1` puts it on every plate: an upright, unmirrored "F↑" means up / down / left / right are right).
 */
internal object Textures {
    enum class Icon { PLAY, PAUSE, REPLAY, NEXT, HERE }

    private fun canvas(size: Int): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return b to Canvas(b)
    }

    private fun paint(color: Color, alpha: Float = 1f, stroke: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.copy(alpha = alpha).toArgb()
            if (stroke > 0f) {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        }

    /**
     * Hand disc face (design `entities3d.handDiscs`): the hand's colour at [fill] alpha and a palm-down hand outline
     * in white 70 % as the user sees their own hand: the thumb points to the body's middle line (right hand: thumb on
     * the left; left hand: thumb on the right).
     */
    fun hand(hand: Hand, fill: Float): Bitmap {
        val n = 256
        val (b, c) = canvas(n)
        val base = if (hand == Hand.RIGHT) JcColors.ScarfCoral else JcColors.ScarfSky
        c.drawCircle(n / 2f, n / 2f, n / 2f - 2f, paint(base, fill))
        val line = paint(Color.White, 0.85f, stroke = n * 0.035f)
        // palm and four fingers (pointing up, away from the wrist), drawn for the right hand and mirrored for the left
        val path = Path()
        val m = if (hand == Hand.RIGHT) 1f else -1f
        fun x(v: Float) = n / 2f + m * (v - 0.5f) * n
        fun y(v: Float) = v * n
        // palm
        path.addRoundRect(RectF(minOf(x(0.36f), x(0.68f)), y(0.46f), maxOf(x(0.36f), x(0.68f)), y(0.78f)), n * 0.06f, n * 0.06f, Path.Direction.CW)
        c.drawPath(path, line)
        // fingers
        val fingers = listOf(0.40f to 0.24f, 0.48f to 0.18f, 0.56f to 0.20f, 0.64f to 0.27f)
        for ((fx, top) in fingers) c.drawLine(x(fx), y(0.46f), x(fx), y(top), line)
        // thumb towards the body's middle line (left of the palm for the right hand)
        c.drawLine(x(0.36f), y(0.62f), x(0.22f), y(0.46f), line)
        return b
    }

    /** Dwell disc face: charcoal disc with a white 0.06 m icon (on the 0.12 m disc: half the width). */
    fun icon(icon: Icon): Bitmap {
        val n = 256
        val (b, c) = canvas(n)
        c.drawCircle(n / 2f, n / 2f, n / 2f - 2f, paint(JcColors.Charcoal, 1f))
        val white = paint(Color.White)
        val line = paint(Color.White, 1f, stroke = n * 0.06f)
        val cx = n / 2f
        val cy = n / 2f
        val r = n * 0.25f
        when (icon) {
            Icon.PLAY ->
                c.drawPath(Path().apply { moveTo(cx - r * 0.6f, cy - r); lineTo(cx + r * 0.9f, cy); lineTo(cx - r * 0.6f, cy + r); close() }, white)
            Icon.PAUSE -> {
                c.drawRoundRect(RectF(cx - r * 0.75f, cy - r, cx - r * 0.2f, cy + r), 8f, 8f, white)
                c.drawRoundRect(RectF(cx + r * 0.2f, cy - r, cx + r * 0.75f, cy + r), 8f, 8f, white)
            }
            Icon.REPLAY -> {
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, line)
                c.drawPath(Path().apply { moveTo(cx + r * 0.95f, cy - r * 1.05f); lineTo(cx + r * 0.95f, cy - r * 0.25f); lineTo(cx + r * 0.2f, cy - r * 0.55f); close() }, white)
            }
            Icon.NEXT -> {
                c.drawPath(Path().apply { moveTo(cx - r * 0.8f, cy - r); lineTo(cx + r * 0.5f, cy); lineTo(cx - r * 0.8f, cy + r); close() }, white)
                c.drawRoundRect(RectF(cx + r * 0.55f, cy - r, cx + r * 0.9f, cy + r), 6f, 6f, white)
            }
            Icon.HERE -> {
                // "bring it here" = re-centre: a ring with four ticks pointing in and a centre dot
                c.drawCircle(cx, cy, r * 0.7f, line)
                for (k in 0 until 4) {
                    val a = Math.toRadians(45.0 + 90.0 * k)
                    val ox = Math.cos(a).toFloat()
                    val oy = Math.sin(a).toFloat()
                    c.drawLine(cx + ox * r * 1.25f, cy + oy * r * 1.25f, cx + ox * r * 0.95f, cy + oy * r * 0.95f, line)
                }
                c.drawCircle(cx, cy, r * 0.22f, white)
            }
        }
        return b
    }

    /** Count-in digit plate: white numeral (design: 70 % via the entity opacity) on a faint charcoal disc. */
    fun digit(d: Int): Bitmap {
        val n = 128
        val (b, c) = canvas(n)
        c.drawCircle(n / 2f, n / 2f, n / 2f - 1f, paint(JcColors.Charcoal, 0.35f))
        val p =
            paint(Color.White).apply {
                textSize = n * 0.78f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        val fm = p.fontMetrics
        c.drawText(d.toString(), n / 2f, n / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }

    /** Orientation test: "F" with an up arrow on a grey disc (asymmetric left / right and up / down). */
    fun testF(): Bitmap {
        val n = 256
        val (b, c) = canvas(n)
        c.drawCircle(n / 2f, n / 2f, n / 2f - 2f, paint(Color(0xFF666666)))
        val p =
            paint(Color.White).apply {
                textSize = n * 0.55f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        c.drawText("F↑", n / 2f, n * 0.68f, p)
        return b
    }
}
