package app.openwispr

import org.futo.voiceinput.shared.SpeechWave
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class DictationPhase { Idle, Recording, Processing }

/** Shared look of the floating bubble and the voice key, so the two always read the same. */
internal object MicVisuals {
    val BrandColors = longArrayOf(0xFF6B6BFF, 0xFFFF7049, 0xFFFFDC35, 0xFF2ECB98, 0xFFF23C91)
    val IdleShape = floatArrayOf(.3f, .7f, 1f, .6f, .25f)
    const val RecordingStart = 0xFFFF7049
    const val RecordingEnd = 0xFFF23C91
    const val ProcessingStart = 0xFF4A3FFF
    const val ProcessingEnd = 0xFF1E168F

    /** Height of bar [i] of 5, 0..1. Speech ripples outward from the middle bar. */
    fun barAmount(phase: DictationPhase, i: Int, speech: SpeechWave, timeSeconds: Float): Float = when (phase) {
        DictationPhase.Idle -> IdleShape[i]
        // Only the voice moves the bars: silence leaves them low and still.
        DictationPhase.Recording ->
            .12f + .88f * min(1f, speech.levelAt(abs(i - 2) * .07f) * 1.3f) * (.55f + .45f * IdleShape[i])
        DictationPhase.Processing ->
            .25f + .75f * ((sin(timeSeconds * 2f * PI.toFloat() * 1.2f - i * .9f) + 1f) / 2f).pow(2)
    }.coerceIn(0f, 1f)
}
