package org.futo.voiceinput.shared.ui

import android.database.ContentObserver
import android.graphics.Matrix
import android.graphics.Path as AndroidPath
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.isActive
import org.futo.voiceinput.shared.SpeechWave
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private val Blue = Color(0xFF3024ED)
private val Coral = Color(0xFFFF7049)
private val Yellow = Color(0xFFFFDC35)
private val Green = Color(0xFF2ECB98)
private val Pink = Color(0xFFF23C91)

private data class Piece(
    val x: Float, val color: Color, val gain: Float,
    val anchor: Float = 188f, val path: Path? = null,
    val dotY: Float = 0f, val radius: Float = 10.5f,
    val direction: Float = 1f, val follow: Float = 0f, val dash: Boolean = false,
)

private fun stroke(x: Float, color: Color, gain: Float, data: String, anchor: Float = 188f, dash: Boolean = false) =
    Piece(x, color, gain, anchor, PathParser().parsePathString(data).toPath(), dash = dash)

private fun logoPieces() = listOf(
    stroke(125f, Coral, .85f, "M125 156V188"),
    stroke(83f, Blue, 1.4f, "M24 188H62V162C62 142 93 142 93 162V203C93 228 125 228 125 203V188"),
    Piece(83f, Blue, 1.1f, dotY = 114f, radius = 13f, direction = -1f),
    Piece(125f, Coral, .8f, dotY = 130f, direction = -1f),
    stroke(153f, Coral, .7f, "M153 253V72Q153 58 167 58", anchor = 58f),
    stroke(181f, Yellow, .8f, "M167 58Q181 58 181 72V239", anchor = 58f),
    Piece(153f, Coral, .3f, dotY = 284f, follow = 195f * .7f * .27f),
    stroke(226f, Green, .65f, "M210 309V133C210 108 243 108 243 133V188"),
    stroke(243f, Pink, 1f, "M243 188V264"),
    Piece(243f, Blue, 0f, dotY = 188f),
    stroke(278f, Green, 1.5f, "M278 158V188"),
    stroke(278f, Pink, 1.2f, "M278 188V212"),
    Piece(278f, Green, 1.1f, dotY = 127f, direction = -1f),
    Piece(278f, Pink, 1.1f, dotY = 242f),
    stroke(324f, Pink, 1f, "M313 188H335", dash = true),
)

private data class Frame(val speech: FloatArray = FloatArray(15), val processingTime: Float = 0f, val mix: Float = 0f)

@Composable
private fun systemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val enabled by produceState(true, resolver) {
        fun update() { value = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        update()
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        awaitDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** Individual vector pieces follow delayed speech energy, with no clock-driven listening loop. */
@Composable
fun OpenWisprLogo(modifier: Modifier, magnitude: Float = 0f, processing: Boolean = false, animated: Boolean = true) {
    val pieces = remember { logoPieces() }
    val currentMagnitude by rememberUpdatedState(magnitude)
    val currentProcessing by rememberUpdatedState(processing)
    val motion = systemMotionEnabled() && animated
    var frame by remember { mutableStateOf(Frame()) }
    LaunchedEffect(motion) {
        frame = Frame()
        if (!motion) return@LaunchedEffect
        val speech = SpeechWave()
        var previous = 0L
        var wasProcessing = false
        while (isActive) withFrameNanos { now ->
            val dt = if (previous == 0L) 0f else min((now - previous) / 1_000_000_000f, .1f)
            previous = now
            speech.advance(if (currentProcessing) 0f else currentMagnitude, dt)
            val mix = frame.mix + ((if (currentProcessing) 1f else 0f) - frame.mix) * (1f - exp(-dt / .12f))
            val processingTime = if (currentProcessing && !wasProcessing) 0f else frame.processingTime + dt
            wasProcessing = currentProcessing
            val levels = FloatArray(pieces.size) { index ->
                // The voice enters on the left, with a 140 ms ripple to the trailing dash.
                speech.levelAt(((pieces[index].x - 83f) / 241f).coerceIn(0f, 1f) * .14f)
            }
            frame = Frame(levels, processingTime, mix)
        }
    }
    Canvas(modifier) {
        val factor = min(size.width, size.height) / 355f
        translate((size.width - 355f * factor) / 2f, (size.height - 355f * factor) / 2f) {
            scale(factor, factor, pivot = Offset.Zero) {
                for ((index, piece) in pieces.withIndex()) {
                    val pulse = ((sin(frame.processingTime / 1.4f * PI.toFloat() * 2f - (piece.x - 83f) / 241f * PI.toFloat() * 1.6f) + 1f) / 2f).pow(3)
                    val amount = frame.speech[index] * 1.5f * (1f - frame.mix) + (pulse - .3f) * 1.5f * .85f * frame.mix
                    val energy = amount * piece.gain
                    val alpha = 1f - frame.mix * .4f * (1f - pulse)
                    if (piece.path == null) {
                        drawCircle(piece.color, piece.radius, Offset(piece.x, piece.dotY + amount * piece.follow + piece.direction * energy * 16f), alpha)
                    } else {
                        val matrix = Matrix().apply {
                            if (piece.dash) {
                                setScale(1f + energy * .28f, 1f, piece.x, piece.anchor)
                                postTranslate(0f, energy * 8f)
                            } else setScale(1f, 1f + energy * .27f, piece.x, piece.anchor)
                        }
                        val path = AndroidPath(piece.path.asAndroidPath()).apply { transform(matrix) }.asComposePath()
                        drawPath(path, piece.color, alpha = alpha, style = Stroke(21f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}
