package app.openwispr

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
import org.futo.voiceinput.shared.SpeechWave
import kotlin.math.min
import kotlin.math.pow

/**
 * The floating mic bubble: a dark disc carrying the OpenWispr bars. Recording turns it warm and
 * drives the bars with the voice alone; transcribing turns it indigo with a travelling wave and a
 * spinning brand-colour ring, so the state reads at a glance without any text.
 */
class FloatingMicBubbleView(context: Context) : View(context) {
    var state = DictationPhase.Idle
        set(value) {
            if (field == value) return
            field = value
            fromColors = currentColors()
            colorProgress = 0f
            level = 0f
            speech = SpeechWave()
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

    private var fromColors = colorsFor(DictationPhase.Idle)
    private var colorProgress = 1f
    /** Recent voice energy; each bar replays it a little later, so speech ripples outward. */
    private var speech = SpeechWave()
    private var lastFrame = 0L

    init {
        // Shadow layers on shapes need software rendering before API 28; the view is small.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun colorsFor(state: DictationPhase) = when (state) {
        DictationPhase.Idle -> intArrayOf(0xFF2E2C66.toInt(), 0xFF15142E.toInt())
        DictationPhase.Recording -> intArrayOf(MicVisuals.RecordingStart.toInt(), MicVisuals.RecordingEnd.toInt())
        DictationPhase.Processing -> intArrayOf(MicVisuals.ProcessingStart.toInt(), MicVisuals.ProcessingEnd.toInt())
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
        if (state == DictationPhase.Recording) speech.advance(level, dt)
        val time = now / 1000f

        val cx = width / 2f
        val cy = height / 2f
        val colors = currentColors()
        disc.shader = LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
            colors[0], colors[1], Shader.TileMode.CLAMP)

        if (state == DictationPhase.Recording) {
            // A soft halo that swells with the voice.
            val swell = speech.levelAt(0f).pow(.7f)
            halo.strokeWidth = (2f + 4f * swell) * density
            halo.color = MicVisuals.RecordingEnd.toInt()
            halo.alpha = (70 + 110 * swell).toInt()
            canvas.drawCircle(cx, cy, radius + (2f + 4f * swell) * density, halo)
        }

        canvas.drawCircle(cx, cy, radius, shadow)
        canvas.drawCircle(cx, cy, radius, disc)
        canvas.drawCircle(cx, cy, radius - density / 2f, rim)

        if (state == DictationPhase.Processing) {
            val r = radius + 4f * density
            arc.set(cx - r, cy - r, cx + r, cy + r)
            val sweep = SweepGradient(cx, cy, IntArray(6) { MicVisuals.BrandColors[it % 5].toInt() }, null)
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
            val half = minHalf + (maxHalf - minHalf) * MicVisuals.barAmount(state, i, speech, time)
            val x = cx + (i - 2) * spacing
            bar.color = if (state == DictationPhase.Idle) MicVisuals.BrandColors[i].toInt() else 0xFFFFFFFF.toInt()
            canvas.drawLine(x, cy - half, x, cy + half, bar)
        }

        if (state != DictationPhase.Idle || colorProgress < 1f) postInvalidateOnAnimation()
        else lastFrame = 0L
    }

    companion object {
        const val RadiusDp = 26f
        /** Window size: the disc plus room for its shadow and the recording halo. */
        const val SizeDp = 76

    }
}
