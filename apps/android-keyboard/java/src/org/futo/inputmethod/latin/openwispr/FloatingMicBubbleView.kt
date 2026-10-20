package org.futo.inputmethod.latin.openwispr

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The floating mic bubble: a dark disc carrying the OpenWispr bars. Recording turns it warm and
 * drives the bars with the voice; transcribing turns it indigo with a travelling wave and a
 * spinning brand-colour ring, so the state reads at a glance without any text.
 */
internal class FloatingMicBubbleView(context: Context) : View(context) {
    enum class State { Idle, Recording, Processing }

    var state = State.Idle
        set(value) {
            if (field == value) return
            field = value
            fromColors = currentColors()
            colorProgress = 0f
            level = 0f
            postInvalidateOnAnimation()
        }

    /** Target voice level, 0..1; the drawn level eases toward it. */
    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            postInvalidateOnAnimation()
        }

    private val density = context.resources.displayMetrics.density
    private val radius = RadiusDp * density

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x00000000
        setShadowLayer(7f * density, 0f, 2.5f * density, 0x66000000)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x29FFFFFF
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.4f * density
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val arc = RectF()
    private val evaluator = ArgbEvaluator()

    private var fromColors = colorsFor(State.Idle)
    private var colorProgress = 1f
    private var shownLevel = 0f
    private var lastFrame = 0L

    init {
        // Shadow layers on shapes need software rendering before API 28; the view is small.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun colorsFor(state: State) = when (state) {
        State.Idle -> intArrayOf(0xFF2E2C66.toInt(), 0xFF15142E.toInt())
        State.Recording -> intArrayOf(0xFFFF7049.toInt(), 0xFFF23C91.toInt())
        State.Processing -> intArrayOf(0xFF4A3FFF.toInt(), 0xFF1E168F.toInt())
    }

    private fun currentColors(): IntArray {
        val to = colorsFor(state)
        return IntArray(2) { evaluator.evaluate(colorProgress, fromColors[it], to[it]) as Int }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(now - lastFrame, 64L) / 1000f
        lastFrame = now
        colorProgress = min(1f, colorProgress + dt / .25f)
        shownLevel += (level - shownLevel) * (1f - exp(-dt / .08f))
        val time = now / 1000f

        val cx = width / 2f
        val cy = height / 2f
        val colors = currentColors()
        disc.shader = LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
            colors[0], colors[1], Shader.TileMode.CLAMP)

        if (state == State.Recording) {
            // A soft halo that swells with the voice.
            val swell = shownLevel.pow(.7f)
            halo.strokeWidth = (2f + 4f * swell) * density
            halo.color = 0xFFF23C91.toInt()
            halo.alpha = (70 + 110 * swell).toInt()
            canvas.drawCircle(cx, cy, radius + (2f + 4f * swell) * density, halo)
        }

        canvas.drawCircle(cx, cy, radius, shadow)
        canvas.drawCircle(cx, cy, radius, disc)
        canvas.drawCircle(cx, cy, radius - density / 2f, rim)

        if (state == State.Processing) {
            val r = radius + 4f * density
            arc.set(cx - r, cy - r, cx + r, cy + r)
            val sweep = SweepGradient(cx, cy, BrandColors + BrandColors[0], null)
            canvas.save()
            canvas.rotate((time * 300f) % 360f, cx, cy)
            ring.shader = sweep
            canvas.drawArc(arc, 0f, 300f, false, ring)
            canvas.restore()
        }

        val spacing = 6.2f * density
        val maxHalf = 9.5f * density
        val minHalf = 1.8f * density
        for (i in 0 until 5) {
            val shape = IdleShape[i]
            val amount = when (state) {
                State.Idle -> shape
                State.Recording -> {
                    val wobble = .6f + .4f * sin(time * 2f * PI.toFloat() * 1.6f + i * 1.3f)
                    // Breathes a little in silence so a quiet room still reads as listening.
                    .16f + .1f * shape + .1f * wobble + .7f * min(1f, shownLevel * 1.4f) * (.45f + .55f * shape) * wobble
                }
                State.Processing -> {
                    val wave = (sin(time * 2f * PI.toFloat() * 1.2f - i * .9f) + 1f) / 2f
                    .25f + .75f * wave.pow(2)
                }
            }
            val half = minHalf + (maxHalf - minHalf) * amount.coerceIn(0f, 1f)
            val x = cx + (i - 2) * spacing
            bar.color = if (state == State.Idle) BrandColors[i] else 0xFFFFFFFF.toInt()
            canvas.drawLine(x, cy - half, x, cy + half, bar)
        }

        if (state != State.Idle || colorProgress < 1f) postInvalidateOnAnimation()
        else lastFrame = 0L
    }

    companion object {
        const val RadiusDp = 26f
        /** Window size: the disc plus room for its shadow and the recording halo. */
        const val SizeDp = 76

        private val BrandColors = intArrayOf(
            0xFF6B6BFF.toInt(), 0xFFFF7049.toInt(), 0xFFFFDC35.toInt(), 0xFF2ECB98.toInt(), 0xFFF23C91.toInt(),
        )
        private val IdleShape = floatArrayOf(.3f, .7f, 1f, .6f, .25f)
    }
}
