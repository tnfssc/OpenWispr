package org.futo.inputmethod.latin.openwispr

import org.futo.voiceinput.shared.AudioTranscriptionBackend
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

class ProviderHttpException(val status: Int) : IOException("Provider request failed ($status)")

fun speechFailureMessage(error: Exception): String = when {
    error is IllegalArgumentException ->
        "Save a provider and API key in voice settings, then retry."
    error is ProviderHttpException && error.status in listOf(401, 403) ->
        "The provider rejected your API key. Check the key in voice settings, then retry."
    error is UnknownHostException || error is ConnectException || error is SocketTimeoutException ->
        "Could not reach the provider. Check your connection, then retry."
    error is ProviderHttpException && error.status == 429 ->
        "The provider is busy or your quota is used up. Wait or check your provider plan, then retry."
    else -> "The provider could not transcribe this recording. Check voice settings, then retry."
}

data class FailedSpeech(val samples: ShortArray, val sampleRateHz: Int, val message: String, val id: String)
data class RecoveredSpeech(val text: String, val id: String)

/** One failed recording, never a history. Use Context.noBackupFilesDir in the app. */
class FailedSpeechStore(directory: File) {
    private val file = File(directory, "openwispr-failed-speech.pcm")
    companion object { private val storageLock = Any() }
    fun exists(): Boolean = file.exists()
    fun message(): String = DataInputStream(file.inputStream()).use { it.readUTF(); it.readInt(); it.readUTF() }

    fun save(samples: ShortArray, count: Int, rate: Int, message: String) = synchronized(storageLock) {
        check(!exists()) { "Retry or discard saved speech before recording again" }
        require(count in 1..samples.size)
        write(FailedSpeech(samples.copyOf(count), rate, message, UUID.randomUUID().toString()))
    }

    private fun write(speech: FailedSpeech) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(temporary.outputStream()).use { output ->
                output.writeUTF(speech.id)
                output.writeInt(speech.sampleRateHz)
                output.writeUTF(speech.message)
                output.writeInt(speech.samples.size)
                speech.samples.forEach { output.writeShort(it.toInt()) }
            }
            check(temporary.renameTo(file)) { "Could not save failed speech" }
        } finally { temporary.delete() }
    }

    fun updateFailure(expectedId: String, message: String) = synchronized(storageLock) {
        if (exists()) {
            val speech = load()
            if (speech.id == expectedId) write(speech.copy(message = message))
        }
    }

    fun load(): FailedSpeech = synchronized(storageLock) {
        DataInputStream(file.inputStream()).use { input ->
            val id = input.readUTF()
            val rate = input.readInt()
            val message = input.readUTF()
            val count = input.readInt()
            require(count > 0 && count.toLong() * 2 <= file.length())
            FailedSpeech(ShortArray(count) { input.readShort() }, rate, message, id)
        }
    }

    fun discard(expectedId: String? = null) = synchronized(storageLock) {
        if (file.exists() && (expectedId == null || DataInputStream(file.inputStream()).use { it.readUTF() } == expectedId)) {
            check(file.delete()) { "Could not discard saved speech" }
        }
    }
}

/** Save only failed final requests. Cancellation and successful audio leave no archive. */
class RecoverableSpeechBackend(
    private val delegate: AudioTranscriptionBackend,
    private val store: FailedSpeechStore,
    private val onFailure: (String) -> Unit = {},
) : AudioTranscriptionBackend {
    @Volatile private var cancelled = false
    override suspend fun transcribe(samples: ShortArray, sampleCount: Int, sampleRateHz: Int): String {
        check(!store.exists()) { "Retry or discard saved speech first" }
        cancelled = false
        return try {
            delegate.transcribe(samples, sampleCount, sampleRateHz)
        } catch (error: Exception) {
            if (error is CancellationException || cancelled) throw error
            val message = speechFailureMessage(error)
            val saved = runCatching { store.save(samples, sampleCount, sampleRateHz, message) }.isSuccess
            onFailure(when {
                saved -> message
                store.exists() -> "Another failed recording is already saved. Retry or discard it first."
                else -> "Transcription failed and this recording could not be saved. Free device storage and try again."
            })
            throw error
        }
    }
    override fun cancel() { cancelled = true; delegate.cancel() }
}

suspend fun retryFailedSpeech(store: FailedSpeechStore, backend: AudioTranscriptionBackend): RecoveredSpeech {
    val speech = store.load()
    val text = try {
        backend.transcribe(speech.samples, speech.samples.size, speech.sampleRateHz)
    } catch (error: Exception) {
        if (error !is CancellationException && kotlinx.coroutines.currentCoroutineContext().isActive) {
            runCatching { store.updateFailure(speech.id, speechFailureMessage(error)) }
        }
        throw error
    }
    // Do not remove saved audio if the component was closed during the request.
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    // The UI acknowledges success only after receiving this result in its active scope.
    return RecoveredSpeech(text, speech.id)
}
