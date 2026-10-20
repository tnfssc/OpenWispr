package org.futo.inputmethod.latin.uix.actions

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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private val BrandColors = listOf(
    Color(0xFF6B6BFF), Color(0xFFFF7049), Color(0xFFFFDC35), Color(0xFF2ECB98), Color(0xFFF23C91),
)
private val IdleShape = floatArrayOf(.3f, .7f, 1f, .6f, .25f)

/**
 * The voice key, drawn like the floating mic bubble: brand bars at rest; a warm disc with bars
 * driven only by the voice while listening; an indigo disc with a travelling wave while transcribing.
 */
@Composable
fun VoiceKeyIcon(modifier: Modifier) {
    val phase by InlineDictation.phase
    val level by rememberUpdatedState(InlineDictation.level.floatValue)
    val speech = remember(phase == InlineDictation.Phase.Recording) { SpeechWave() }
    var frameTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) {
        if (phase == InlineDictation.Phase.Idle) return@LaunchedEffect
        var previous = 0L
        while (isActive) withFrameNanos { now ->
            val dt = if (previous == 0L) 0f else min((now - previous) / 1_000_000_000f, .1f)
            previous = now
            if (phase == InlineDictation.Phase.Recording) speech.advance(level, dt)
            frameTime = now
        }
    }
    val warm by animateFloatAsState(if (phase == InlineDictation.Phase.Recording) 1f else 0f, tween(220), label = "warm")
    val cool by animateFloatAsState(if (phase == InlineDictation.Phase.Processing) 1f else 0f, tween(220), label = "cool")

    Canvas(modifier) {
        val time = frameTime / 1_000_000_000f
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val active = maxOf(warm, cool)
        if (active > 0f) {
            val start = lerp(Color(0xFF4A3FFF), Color(0xFFFF7049), warm / (warm + cool).coerceAtLeast(.001f))
            val end = lerp(Color(0xFF1E168F), Color(0xFFF23C91), warm / (warm + cool).coerceAtLeast(.001f))
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
            val amount = when (phase) {
                InlineDictation.Phase.Idle -> IdleShape[i]
                // Only the voice moves the bars: silence leaves them low and still.
                InlineDictation.Phase.Recording ->
                    .12f + .88f * min(1f, speech.levelAt(abs(i - 2) * .07f) * 1.3f) * (.55f + .45f * IdleShape[i])
                InlineDictation.Phase.Processing ->
                    .25f + .75f * ((sin(time * 2f * PI.toFloat() * 1.2f - i * .9f) + 1f) / 2f).pow(2)
            }
            val half = minHalf + (maxHalf - minHalf) * amount.coerceIn(0f, 1f)
            val x = center.x + (i - 2) * spacing
            drawLine(
                lerp(BrandColors[i], Color.White, active),
                Offset(x, center.y - half), Offset(x, center.y + half),
                strokeWidth = radius * .14f, cap = StrokeCap.Round,
            )
        }
    }
}
