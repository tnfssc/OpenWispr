package org.futo.voiceinput.shared

/**
 * Speech backend used after microphone capture.
 *
 * [samples] is a reusable backing array; implementations must read only
 * [sampleCount] entries and must not retain the array after [transcribe] returns.
 */
interface AudioTranscriptionBackend {
    suspend fun transcribe(
        samples: ShortArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ): String

    fun cancel()
}
