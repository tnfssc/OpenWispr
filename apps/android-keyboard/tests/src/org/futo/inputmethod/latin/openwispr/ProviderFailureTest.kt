package org.futo.inputmethod.latin.openwispr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class ProviderFailureTest {
    @Test fun rejectedKeyHasUsefulCopyEvenWithoutResponseBody() = runBlocking {
        val connection = object : HttpURLConnection(URL("https://example.invalid")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getResponseCode() = 401
            override fun getInputStream(): java.io.InputStream = throw AssertionError("Do not read rejected response body")
        }
        val backend = OpenWisprTranscriptionBackend(OpenWisprConfig(groqApiKey = "test-key")) { connection }
        val error = runCatching { backend.transcribe(shortArrayOf(1), 1, 16000) }.exceptionOrNull()
        assertTrue(error is ProviderHttpException)
        assertEquals(401, (error as ProviderHttpException).status)
        assertTrue(speechFailureMessage(error).contains("API key"))
    }
}
