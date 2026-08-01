package org.futo.inputmethod.latin.openwispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpenWisprBackendTest {
    @Test
    fun `provider selection keeps separate credentials and models`() {
        val config = OpenWisprConfig(
            provider = OpenWisprProvider.OPEN_ROUTER,
            groqApiKey = "groq-key",
            openRouterApiKey = "router-key",
            groqModel = "groq-model",
            openRouterModel = "router-model",
        )

        assertEquals("router-key", config.apiKey)
        assertEquals("router-model", config.model)
        assertTrue(config.isConfigured)
        assertEquals("groq-key", config.keyFor(OpenWisprProvider.GROQ))
    }

    @Test
    fun `wav encoder writes canonical mono pcm header`() {
        val output = ByteArrayOutputStream()
        WavPcmWriter.write(output, shortArrayOf(0x1234, -2), sampleCount = 2, sampleRateHz = 16_000)
        val bytes = output.toByteArray()
        val littleEndian = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(40, littleEndian.getInt(4))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(16_000, littleEndian.getInt(24))
        assertEquals(32_000, littleEndian.getInt(28))
        assertEquals(4, littleEndian.getInt(40))
        assertEquals(0x34, bytes[44].toInt() and 0xFF)
        assertEquals(0x12, bytes[45].toInt() and 0xFF)
    }

}
