package org.futo.voiceinput.shared

/** Optional local preview. It never owns the microphone or supplies the final transcript. */
interface AudioPreview {
    fun start(sampleRateHz: Int)
    /** Called on the recorder thread. Must not block; the samples are reused after this call. */
    fun accept(samples: ShortArray, sampleCount: Int)
    fun close()
}
