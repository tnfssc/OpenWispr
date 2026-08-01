package org.futo.inputmethod.latin.openwispr

import java.io.OutputStream
import java.nio.charset.StandardCharsets

object WavPcmWriter {
    const val HEADER_BYTES = 44
    private const val BYTES_PER_SAMPLE = 2

    fun write(
        output: OutputStream,
        samples: ShortArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ) {
        require(sampleCount in 0..samples.size)
        require(sampleRateHz > 0)
        val pcmBytes = sampleCount * BYTES_PER_SAMPLE
        writeAscii(output, "RIFF")
        writeLittleEndianInt(output, 36 + pcmBytes)
        writeAscii(output, "WAVEfmt ")
        writeLittleEndianInt(output, 16)
        writeLittleEndianShort(output, 1)
        writeLittleEndianShort(output, 1)
        writeLittleEndianInt(output, sampleRateHz)
        writeLittleEndianInt(output, sampleRateHz * BYTES_PER_SAMPLE)
        writeLittleEndianShort(output, BYTES_PER_SAMPLE)
        writeLittleEndianShort(output, 16)
        writeAscii(output, "data")
        writeLittleEndianInt(output, pcmBytes)
        for (index in 0 until sampleCount) {
            writeLittleEndianShort(output, samples[index].toInt())
        }
    }

    private fun writeLittleEndianInt(output: OutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write(value ushr 8 and 0xFF)
        output.write(value ushr 16 and 0xFF)
        output.write(value ushr 24 and 0xFF)
    }

    private fun writeLittleEndianShort(output: OutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write(value ushr 8 and 0xFF)
    }

    private fun writeAscii(output: OutputStream, value: String) {
        output.write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}
