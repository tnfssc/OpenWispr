package org.futo.inputmethod.latin.openwispr

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.uix.forceUnlockDatastore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OpenWisprConfigStoreTest {
    @Test
    fun configurationAndEncryptedCredentialsSurviveReload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        forceUnlockDatastore(context)
        val expected = OpenWisprConfig(
            provider = OpenWisprProvider.OPEN_ROUTER,
            groqApiKey = "test-groq-key",
            openRouterApiKey = "test-router-key",
            openRouterModel = "test-transcription-model",
            language = "es",
            refinementEnabled = true,
            refinementProvider = OpenWisprProvider.GROQ,
            groqRefinementModel = "test-refinement-model",
            refinementPrompt = "Return cleaned transcript only.",
        )

        OpenWisprConfigStore.save(context, expected)
        val actual = OpenWisprConfigStore.load(context)

        assertEquals(expected.provider, actual.provider)
        assertEquals(expected.openRouterApiKey, actual.openRouterApiKey)
        assertEquals(expected.openRouterModel, actual.openRouterModel)
        assertEquals(expected.language, actual.language)
        assertEquals(expected.refinementEnabled, actual.refinementEnabled)
        assertEquals(expected.groqRefinementModel, actual.groqRefinementModel)
        assertTrue(actual.secureStorageAvailable)
    }

    @Test
    fun groqBackendStreamsWavAndParsesTranscript() = runBlocking {
        val connection = FakeConnection("{\"text\":\"direct transcript\"}")
        val backend = OpenWisprTranscriptionBackend(
            config = OpenWisprConfig(groqApiKey = "secret", groqModel = "whisper-test"),
            connectionFactory = { connection },
        )

        val result = backend.transcribe(
            samples = shortArrayOf(1, 2, 3),
            sampleCount = 3,
            sampleRateHz = 16_000,
        )

        assertEquals("direct transcript", result)
        assertEquals("Bearer secret", connection.getRequestProperty("Authorization"))
        val request = connection.requestBody.toString(Charsets.ISO_8859_1.name())
        assertTrue(request.contains("RIFF"))
        assertTrue(request.contains("whisper-test"))
    }

    @Test
    fun openRouterBackendStreamsBase64AudioAndParsesTranscript() = runBlocking {
        val connection = FakeConnection(
            """{"choices":[{"message":{"content":"router transcript"}}]}""",
        )
        val backend = OpenWisprTranscriptionBackend(
            config = OpenWisprConfig(
                provider = OpenWisprProvider.OPEN_ROUTER,
                openRouterApiKey = "router-secret",
                openRouterModel = "audio-model",
            ),
            connectionFactory = { connection },
        )

        val result = backend.transcribe(shortArrayOf(4, 5, 6), 3, 16_000)

        assertEquals("router transcript", result)
        val request = connection.requestBody.toString(Charsets.UTF_8.name())
        val json = org.json.JSONObject(request)
        assertEquals("audio-model", json.getString("model"))
        val audio = json.getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
            .getJSONObject(1)
            .getJSONObject("input_audio")
            .getString("data")
        assertTrue(audio.isNotBlank())
    }

    @Test
    fun refinementUsesConfiguredProviderAndReturnsCleanedText() = runBlocking {
        val transcriptionConnection = FakeConnection("""{"text":"raw words"}""")
        val refinementConnection = FakeConnection(
            """{"choices":[{"message":{"content":"Raw words."}}]}""",
        )
        val connections = ArrayDeque(
            listOf(
                transcriptionConnection,
                refinementConnection,
            ),
        )
        val backend = OpenWisprTranscriptionBackend(
            config = OpenWisprConfig(
                groqApiKey = "secret",
                refinementEnabled = true,
                refinementProvider = OpenWisprProvider.GROQ,
                refinementPrompt = "Clean transcript only.",
            ),
            connectionFactory = { connections.removeFirst() },
        )

        assertEquals("Raw words.", backend.transcribe(shortArrayOf(1, 2), 2, 16_000))
        val request = org.json.JSONObject(
            refinementConnection.requestBody.toString(Charsets.UTF_8.name()),
        )
        assertEquals("qwen/qwen3.6-27b", request.getString("model"))
        assertEquals("none", request.getString("reasoning_effort"))
        assertEquals("hidden", request.getString("reasoning_format"))
    }

    private class FakeConnection(private val response: String) :
        HttpURLConnection(URL("https://test.invalid")) {
        val requestBody = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getOutputStream() = requestBody
        override fun getResponseCode(): Int = 200
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
    }
}
