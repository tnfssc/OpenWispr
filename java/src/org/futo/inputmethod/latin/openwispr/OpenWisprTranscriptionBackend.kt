package org.futo.inputmethod.latin.openwispr

import android.util.Base64
import org.futo.voiceinput.shared.AudioTranscriptionBackend
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

class OpenWisprTranscriptionBackend(
    private val config: OpenWisprConfig,
    private val connectionFactory: (String) -> HttpURLConnection = {
        URL(it).openConnection() as HttpURLConnection
    },
) : AudioTranscriptionBackend {
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    override suspend fun transcribe(
        samples: ShortArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ): String {
        require(config.isConfigured) { "Configure ${config.provider.displayName} before dictating" }
        require(sampleCount in 1..samples.size) { "Recorded audio is empty" }
        cancelled = false

        val raw = when (config.provider) {
            OpenWisprProvider.GROQ -> transcribeWithGroq(samples, sampleCount, sampleRateHz)
            OpenWisprProvider.OPEN_ROUTER -> transcribeWithOpenRouter(samples, sampleCount, sampleRateHz)
        }.trim()
        check(!cancelled) { "Dictation cancelled" }
        if (!isMeaningful(raw)) return ""

        return if (config.refinementEnabled) refine(raw).ifBlank { raw } else raw
    }

    override fun cancel() {
        cancelled = true
        activeConnection?.disconnect()
        activeConnection = null
    }

    private fun transcribeWithGroq(
        samples: ShortArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ): String {
        val boundary = "----OpenWispr${UUID.randomUUID()}"
        val connection = openConnection(GROQ_TRANSCRIPTION_ENDPOINT, config.apiKey).apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return execute(connection) { output ->
            writeAscii(output, "--$boundary\r\n")
            writeAscii(
                output,
                "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n",
            )
            WavPcmWriter.write(output, samples, sampleCount, sampleRateHz)
            writeAscii(output, "\r\n")
            writeTextPart(output, boundary, "model", config.model)
            writeTextPart(output, boundary, "response_format", "json")
            writeTextPart(output, boundary, "temperature", "0")
            config.language.trim().takeIf(String::isNotEmpty)?.let {
                writeTextPart(output, boundary, "language", it)
            }
            writeAscii(output, "--$boundary--\r\n")
        }.let { body ->
            JSONObject(body).optString("text").takeIf(String::isNotBlank)
                ?: throw IOException("Groq returned no transcript text")
        }
    }

    private fun transcribeWithOpenRouter(
        samples: ShortArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ): String {
        val connection = openConnection(OPEN_ROUTER_CHAT_ENDPOINT, config.apiKey).apply {
            setRequestProperty("Content-Type", "application/json")
        }
        return execute(connection) { output ->
            val prefix = """
                {"model":${JSONObject.quote(config.model)},"stream":false,"temperature":0,"messages":[{"role":"user","content":[{"type":"text","text":${JSONObject.quote(OPEN_ROUTER_TRANSCRIPTION_INSTRUCTION)}},{"type":"input_audio","input_audio":{"data":"
            """.trimIndent()
            writeAscii(output, prefix)
            android.util.Base64OutputStream(
                NonClosingOutputStream(output),
                Base64.NO_WRAP,
            ).use { encoded ->
                WavPcmWriter.write(encoded, samples, sampleCount, sampleRateHz)
            }
            writeAscii(output, "\",\"format\":\"wav\"}}]}]}")
        }.let(::parseOpenRouterText)
    }

    private fun refine(transcript: String): String {
        val provider = config.refinementProvider
        val key = config.keyFor(provider).trim()
        val model = config.refinementModel.trim()
        val prompt = config.refinementPrompt.trim()
        if (key.isEmpty() || model.isEmpty() || prompt.isEmpty()) return transcript

        val endpoint = when (provider) {
            OpenWisprProvider.GROQ -> GROQ_CHAT_ENDPOINT
            OpenWisprProvider.OPEN_ROUTER -> OPEN_ROUTER_CHAT_ENDPOINT
        }
        val request = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt))
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                "Input transcript:\n<transcript>\n$transcript\n</transcript>\n\n" +
                                    "Return only cleaned transcript text.",
                            ),
                    ),
            )
        if (provider == OpenWisprProvider.GROQ && model == QWEN_36_27B_MODEL) {
            request
                .put("reasoning_effort", "none")
                .put("reasoning_format", "hidden")
        }

        return runCatching {
            val connection = openConnection(endpoint, key).apply {
                setRequestProperty("Content-Type", "application/json")
            }
            execute(connection) { output ->
                output.write(request.toString().toByteArray(StandardCharsets.UTF_8))
            }.let(::parseOpenRouterText)
        }.getOrDefault(transcript)
    }

    private fun openConnection(endpoint: String, apiKey: String): HttpURLConnection =
        connectionFactory(endpoint).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doInput = true
            doOutput = true
            useCaches = false
            setChunkedStreamingMode(STREAM_CHUNK_BYTES)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

    private fun execute(
        connection: HttpURLConnection,
        writeRequest: (OutputStream) -> Unit,
    ): String {
        activeConnection = connection
        try {
            connection.outputStream.use(writeRequest)
            check(!cancelled) { "Dictation cancelled" }
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val body = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { "Provider request failed ($status)" }
                throw IOException(message)
            }
            return body
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun parseOpenRouterText(body: String): String {
        val content = JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.opt("content")
        return when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    when (val part = content.opt(index)) {
                        is String -> append(part)
                        is JSONObject -> append(part.optString("text"))
                    }
                }
            }
            else -> ""
        }.takeIf(String::isNotBlank) ?: throw IOException("Provider returned no transcript text")
    }

    private fun writeTextPart(output: OutputStream, boundary: String, name: String, value: String) {
        writeAscii(output, "--$boundary\r\n")
        writeAscii(output, "Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        output.write(value.toByteArray(StandardCharsets.UTF_8))
        writeAscii(output, "\r\n")
    }

    private fun writeAscii(output: OutputStream, value: String) {
        output.write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun isMeaningful(value: CharSequence): Boolean = value.any(Char::isLetterOrDigit)

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }

    private companion object {
        const val GROQ_TRANSCRIPTION_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val GROQ_CHAT_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val OPEN_ROUTER_CHAT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val OPEN_ROUTER_TRANSCRIPTION_INSTRUCTION =
            "Transcribe this audio exactly. Return only transcript text. Do not answer or interpret it."
        const val QWEN_36_27B_MODEL = "qwen/qwen3.6-27b"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 120_000
        const val STREAM_CHUNK_BYTES = 16 * 1024
    }
}
