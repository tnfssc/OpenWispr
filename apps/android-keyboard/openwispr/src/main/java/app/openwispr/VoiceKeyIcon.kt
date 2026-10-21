package app.openwispr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.isActive
import org.futo.voiceinput.shared.SpeechWave
import kotlin.math.min

/**
 * The voice key, drawn like the floating mic bubble: brand bars at rest; a warm disc with bars
 * driven only by the voice while listening; an indigo disc with a travelling wave while transcribing.
 */
@Composable
fun VoiceKeyIcon(phase: DictationPhase, level: Float, modifier: Modifier) {
    val currentLevel by rememberUpdatedState(level)
    val speech = remember(phase == DictationPhase.Recording) { SpeechWave() }
    var frameTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) {
        if (phase == DictationPhase.Idle) return@LaunchedEffect
        var previous = 0L
        while (isActive) withFrameNanos { now ->
            val dt = if (previous == 0L) 0f else min((now - previous) / 1_000_000_000f, .1f)
            previous = now
            if (phase == DictationPhase.Recording) speech.advance(currentLevel, dt)
            frameTime = now
        }
    }
    val warm by animateFloatAsState(if (phase == DictationPhase.Recording) 1f else 0f, tween(220), label = "warm")
    val cool by animateFloatAsState(if (phase == DictationPhase.Processing) 1f else 0f, tween(220), label = "cool")

    Canvas(modifier) {
        val time = frameTime / 1_000_000_000f
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val active = maxOf(warm, cool)
        if (active > 0f) {
            val start = lerp(Color(MicVisuals.ProcessingStart), Color(MicVisuals.RecordingStart), warm / (warm + cool).coerceAtLeast(.001f))
            val end = lerp(Color(MicVisuals.ProcessingEnd), Color(MicVisuals.RecordingEnd), warm / (warm + cool).coerceAtLeast(.001f))
            drawCircle(
                Brush.linearGradient(listOf(start, end), center - Offset(radius, radius), center + Offset(radius, radius)),
                radius = radius * (.75f + .25f * active),
                center = center,
                alpha = active,
            )
        }
        val spacing = radius * .24f
        val maxHalf = radius * .44f
        val minHalf = radius * .08f
        for (i in 0 until 5) {
            val half = minHalf + (maxHalf - minHalf) * MicVisuals.barAmount(phase, i, speech, time)
            val x = center.x + (i - 2) * spacing
            drawLine(
                lerp(Color(MicVisuals.BrandColors[i]), Color.White, active),
                Offset(x, center.y - half), Offset(x, center.y + half),
                strokeWidth = radius * .14f, cap = StrokeCap.Round,
            )
        }
    }
}
