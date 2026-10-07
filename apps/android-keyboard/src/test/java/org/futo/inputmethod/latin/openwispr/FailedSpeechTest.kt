package org.futo.inputmethod.latin.openwispr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import org.futo.voiceinput.shared.AudioTranscriptionBackend
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.net.UnknownHostException

class FailedSpeechTest {
    private fun store() = FailedSpeechStore(Files.createTempDirectory("failed-speech-test").toFile().apply { deleteOnExit() })
    private fun backend(block: suspend () -> String) = object : AudioTranscriptionBackend {
        override suspend fun transcribe(samples: ShortArray, sampleCount: Int, sampleRateHz: Int) = block()
        override fun cancel() = Unit
    }

    @Test fun pcmPersistsAcrossRecreation() = runBlocking {
        val directory = Files.createTempDirectory("speech-reopen").toFile()
        val first = FailedSpeechStore(directory)
        val samples = shortArrayOf(10, -20, 999)
        runCatching { RecoverableSpeechBackend(backend { throw UnknownHostException() }, first).transcribe(samples, 2, 16000) }
        samples.fill(0)
        val reopened = FailedSpeechStore(directory).load()
        assertArrayEquals(shortArrayOf(10, -20), reopened.samples)
        assertEquals(16000, reopened.sampleRateHz)
        assertTrue(reopened.message.contains("connection"))
        first.discard()
        assertTrue(directory.delete())
    }

    @Test fun newRecordingCannotReplaceSavedSpeech() = runBlocking {
        val store = store()
        store.save(shortArrayOf(42), 1, 16000, "Saved")
        var called = false
        assertTrue(runCatching { RecoverableSpeechBackend(backend { called = true; "new" }, store).transcribe(shortArrayOf(99), 1, 16000) }.isFailure)
        assertFalse(called)
        assertTrue(runCatching { store.save(shortArrayOf(99), 1, 16000, "new") }.isFailure)
        assertArrayEquals(shortArrayOf(42), store.load().samples)
        store.discard()
    }

    @Test fun retryFailureKeepsSpeechAndSuccessRemovesIt() = runBlocking {
        val store = store()
        store.save(shortArrayOf(42), 1, 16000, "Saved")
        assertTrue(runCatching { retryFailedSpeech(store, backend { throw ProviderHttpException(401) }) }.isFailure)
        assertTrue(store.exists())
        assertTrue(store.message().contains("API key"))
        assertArrayEquals(shortArrayOf(42), store.load().samples)
        var received = false
        val currentBackend = object : AudioTranscriptionBackend {
            override suspend fun transcribe(samples: ShortArray, sampleCount: Int, sampleRateHz: Int): String {
                assertArrayEquals(shortArrayOf(42), samples)
                assertEquals(16000, sampleRateHz)
                received = true
                return "recovered"
            }
            override fun cancel() = Unit
        }
        val recovered = retryFailedSpeech(store, currentBackend)
        assertEquals("recovered", recovered.text)
        assertTrue(store.exists()) // Until the active component accepts the result.
        store.discard(recovered.id)
        assertTrue(received)
        assertFalse(store.exists())
    }

    @Test fun successAndCancellationDoNotCreateAnArchive() = runBlocking {
        val store = store()
        assertEquals("ok", RecoverableSpeechBackend(backend { "ok" }, store).transcribe(shortArrayOf(1), 1, 16000))
        assertFalse(store.exists())
        runCatching { RecoverableSpeechBackend(backend { throw CancellationException() }, store).transcribe(shortArrayOf(1), 1, 16000) }
        assertFalse(store.exists())
        lateinit var wrapper: RecoverableSpeechBackend
        wrapper = RecoverableSpeechBackend(backend { wrapper.cancel(); throw IOException() }, store)
        runCatching { wrapper.transcribe(shortArrayOf(1), 1, 16000) }
        assertFalse(store.exists())
    }

    @Test fun closingRetryPreservesAudio() = runBlocking {
        val store = store()
        store.save(shortArrayOf(1), 1, 16000, "Saved")
        runCatching { retryFailedSpeech(store, backend { throw CancellationException() }) }
        assertTrue(store.exists())
        store.discard()
        assertFalse(store.exists())
    }

    @Test fun credentialAndNetworkCopyAreDistinct() {
        assertTrue(speechFailureMessage(ProviderHttpException(403)).contains("API key"))
        assertTrue(speechFailureMessage(UnknownHostException()).contains("connection"))
        assertTrue(speechFailureMessage(ProviderHttpException(429)).contains("quota"))
        assertFalse(speechFailureMessage(ProviderHttpException(500)).contains("API key"))
    }
    @Test fun oldRetryCannotDeleteANewerFailure() = runBlocking {
        val store = store()
        store.save(shortArrayOf(1), 1, 16000, "First")
        val recovered = retryFailedSpeech(store, backend { "old" })
        store.discard() // Explicit user action, potentially from another component.
        store.save(shortArrayOf(2), 1, 16000, "Second")
        store.discard(recovered.id)
        assertArrayEquals(shortArrayOf(2), store.load().samples)
        store.discard()
    }

    @Test fun cancelledComponentRejectsLateSuccess() = runBlocking {
        val store = store()
        store.save(shortArrayOf(1), 1, 16000, "Saved")
        var delivered = false
        val task = launch {
            retryFailedSpeech(store, backend { currentCoroutineContext().cancel(); "late" })
            delivered = true
        }
        task.join()
        assertFalse(delivered)
        assertTrue(store.exists())
        store.discard()
    }

}
