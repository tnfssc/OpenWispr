package org.futo.voiceinput.shared

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Locale

/** Android 13+ can feed the existing capture to the on-device recognizer through a pipe. */
@RequiresApi(33)
class OnDeviceSpeechPreview(
    private val context: Context,
    private val scope: CoroutineScope,
    private val language: String,
    private val onPartial: (String) -> Unit,
) : AudioPreview {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session: Session? = null

    private class Session(
        val recognizer: SpeechRecognizer,
        val read: ParcelFileDescriptor,
        val write: ParcelFileDescriptor.AutoCloseOutputStream,
    ) {
        val chunks = Channel<ByteArray>(16)
        val transcript = PreviewTranscript()
        var writer: Job? = null
    }

    override fun start(sampleRateHz: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        close()
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val pipe = try { ParcelFileDescriptor.createPipe() } catch (error: Exception) {
                recognizer.destroy()
                throw error
            }
            val current = Session(recognizer, pipe[0], ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
            session = current
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, current.read)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRateHz)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                private fun text(results: Bundle?) = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                private fun emit(text: String) {
                    if (session === current && text.isNotBlank()) onPartial(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    emit(current.transcript.partial(text(partialResults)))
                }
                override fun onSegmentResults(segmentResults: Bundle) {
                    emit(current.transcript.segment(text(segmentResults)))
                }
                override fun onResults(results: Bundle?) {
                    emit(current.transcript.partial(text(results)))
                    stop(current)
                }
                override fun onEndOfSegmentedSession() = stop(current)
                override fun onError(error: Int) = stop(current)
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            // Only installed local models are eligible. No network recognizer or download fallback.
            recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    if (session !== current) return
                    val requested = Locale.forLanguageTag(language)
                    val installed = recognitionSupport.installedOnDeviceLanguages
                    val supported = installed.firstOrNull { it.equals(language, ignoreCase = true) }
                        ?: installed.firstOrNull { Locale.forLanguageTag(it).language == requested.language }
                    if (supported == null) { stop(current); return }
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, supported)
                    try {
                        recognizer.startListening(intent)
                        current.writer = scope.launch(Dispatchers.IO) {
                            try {
                                for (chunk in current.chunks) current.write.write(chunk)
                            } catch (_: Exception) {
                                main.post { stop(current) }
                            }
                        }
                    } catch (_: Exception) { stop(current) }
                }
                override fun onError(error: Int) = stop(current)
            })
        } catch (_: Exception) { close() }
    }

    override fun accept(samples: ShortArray, sampleCount: Int) {
        val current = session ?: return
        if (!current.chunks.trySend(previewPcmBytes(samples, sampleCount)).isSuccess) {
            // A slow recognizer must never delay capture or grow an unbounded queue.
            main.post { stop(current) }
        }
    }

    private fun stop(current: Session) {
        if (session === current) close()
    }

    override fun close() {
        val current = session ?: return
        session = null // Reject late results immediately, including during provider processing.
        current.chunks.close()
        current.writer?.cancel()
        runCatching { current.write.close() }
        runCatching { current.read.close() }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { current.recognizer.destroy() }
        } else {
            main.post { runCatching { current.recognizer.destroy() } }
        }
    }
}
