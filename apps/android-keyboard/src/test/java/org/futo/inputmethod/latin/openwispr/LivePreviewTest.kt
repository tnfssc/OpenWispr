package org.futo.inputmethod.latin.openwispr

import org.futo.voiceinput.shared.PreviewTranscript
import org.futo.voiceinput.shared.previewPcmBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePreviewTest {
    @Test
    fun `partial corrections replace the current phrase without duplicating it`() {
        val transcript = PreviewTranscript()
        assertEquals("pick up a copy", transcript.partial("pick up a copy"))
        assertEquals("pick up a coffee", transcript.partial("pick up a coffee"))
    }

    @Test
    fun `completed segments remain ahead of later partial results`() {
        val transcript = PreviewTranscript()
        assertEquals("Remind me", transcript.segment(" Remind me "))
        assertEquals("Remind me to pick up", transcript.partial("to pick up"))
        assertEquals("Remind me to pick up coffee", transcript.segment("to pick up coffee"))
        assertEquals("Remind me to pick up coffee on the way home", transcript.partial("on the way home"))
    }

    @Test
    fun `empty callbacks preserve completed segments`() {
        val transcript = PreviewTranscript()
        transcript.segment("First sentence.")
        assertEquals("First sentence.", transcript.segment(" "))
        assertEquals("First sentence.", transcript.partial(""))
    }

    @Test
    fun `empty callbacks retain the last provisional phrase`() {
        val transcript = PreviewTranscript()
        transcript.segment("Remind me")
        transcript.partial("to pick up coffee")
        assertEquals("Remind me to pick up coffee", transcript.partial(" "))
        assertEquals("Remind me to pick up coffee", transcript.segment(""))
    }

    @Test
    fun `preview receives an owned little endian snapshot of only recorded samples`() {
        val samples = shortArrayOf(0x1234, -2, Short.MIN_VALUE, 999)
        val encoded = previewPcmBytes(samples, 3)
        samples.fill(0)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte(), 0, 0x80.toByte()), encoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `preview rejects lengths outside the recorded buffer`() {
        previewPcmBytes(shortArrayOf(1), 2)
    }
}
