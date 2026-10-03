package org.futo.voiceinput.shared

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Only the valid part of an AudioRecord read contributes to the meter. */
fun pcmRms(samples: ShortArray, sampleCount: Int): Float {
    require(sampleCount in 0..samples.size)
    if (sampleCount == 0) return 0f
    var squares = 0.0
    repeat(sampleCount) { index ->
        val sample = samples[index].toDouble() / 32768.0
        squares += sample * sample
    }
    return sqrt(squares / sampleCount).toFloat()
}

/** Keep normal speech dynamics instead of saturating most syllables near one. */
fun speechLevel(rms: Float): Float {
    if (!rms.isFinite() || rms <= 0f) return 0f
    val decibels = 20f * log10(rms.coerceAtMost(1f))
    return ((decibels + 60f) / 42f).coerceIn(0f, 1f).pow(1.1f)
}

/** A short history of real speech energy lets a syllable travel across the logo. */
class SpeechWave {
    private val times = DoubleArray(32) { Double.NEGATIVE_INFINITY }
    private val levels = FloatArray(32)
    private var cursor = 0
    private var time = 0.0
    private var envelope = 0f

    fun advance(level: Float, deltaSeconds: Float) {
        val dt = deltaSeconds.coerceIn(0f, .1f)
        val target = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        val response = if (target > envelope) .025f else .12f
        envelope += (target - envelope) * (1f - exp(-dt / response))
        time += dt
        cursor = (cursor + 1) % levels.size
        times[cursor] = time
        levels[cursor] = envelope
    }

    fun levelAt(delaySeconds: Float): Float {
        val targetTime = time - delaySeconds.coerceAtLeast(0f)
        var newer = cursor
        repeat(levels.size - 1) {
            val older = (newer + levels.size - 1) % levels.size
            if (!times[older].isFinite()) return 0f
            if (times[older] <= targetTime) {
                val span = times[newer] - times[older]
                if (span <= 0.0) return levels[newer]
                val mix = ((targetTime - times[older]) / span).coerceIn(0.0, 1.0).toFloat()
                return levels[older] + (levels[newer] - levels[older]) * mix
            }
            newer = older
        }
        return levels[newer]
    }
}
