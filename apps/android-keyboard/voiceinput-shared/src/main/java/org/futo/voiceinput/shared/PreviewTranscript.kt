package org.futo.voiceinput.shared

/** Segmented recognizers send a partial for the current segment, not the whole recording. */
class PreviewTranscript {
    private val segments = mutableListOf<String>()
    private var currentPartial = ""

    fun partial(text: String): String {
        text.trim().takeIf(String::isNotBlank)?.let { currentPartial = it }
        return (segments + currentPartial).filter(String::isNotBlank).joinToString(" ")
    }

    fun segment(text: String): String {
        text.trim().takeIf(String::isNotBlank)?.let {
            segments.add(it)
            currentPartial = ""
        }
        return partial("")
    }
}

fun previewPcmBytes(samples: ShortArray, sampleCount: Int): ByteArray {
    require(sampleCount in 0..samples.size)
    return ByteArray(sampleCount * 2).also { bytes ->
        repeat(sampleCount) { index ->
            bytes[index * 2] = samples[index].toByte()
            bytes[index * 2 + 1] = (samples[index].toInt() shr 8).toByte()
        }
    }
}
