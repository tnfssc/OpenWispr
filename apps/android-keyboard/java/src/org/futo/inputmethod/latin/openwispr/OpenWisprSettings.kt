package org.futo.inputmethod.latin.openwispr

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import app.openwispr.FailedSpeechStore
import app.openwispr.OpenWisprConfig
import app.openwispr.OpenWisprTranscriptionBackend
import app.openwispr.RecoverableSpeechBackend
import kotlinx.coroutines.CoroutineScope
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.voiceinput.shared.AudioPreview
import org.futo.voiceinput.shared.AudioRecognizer
import org.futo.voiceinput.shared.AudioRecognizerSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.types.AudioRecognizerListener

/** Silence before recording stops on its own; 0 keeps recording until the user taps stop. */
val VAD_SILENCE_TIMEOUT_MS = SettingsKey(
    key = intPreferencesKey("vad_silence_timeout_ms"),
    default = 5000
)

/**
 * Whether the floating mic bubble shows. Separate from the accessibility service being enabled,
 * which only Android settings can change, so the keyboard toolbar can switch the bubble quickly.
 */
val FLOATING_MIC_BUBBLE = SettingsKey(
    key = booleanPreferencesKey("floating_mic_bubble"),
    default = true
)

/**
 * A recognizer that records with the user's voice settings and transcribes through the configured
 * provider. Audio that fails to transcribe is kept in [failedSpeech]; [onFailure] gets the reason.
 */
fun openWisprRecognizer(
    context: Context,
    scope: CoroutineScope,
    listener: AudioRecognizerListener,
    config: OpenWisprConfig,
    failedSpeech: FailedSpeechStore,
    livePreview: AudioPreview? = null,
    onFailure: (String) -> Unit,
) = AudioRecognizer(
    context = context,
    lifecycleScope = scope,
    listener = listener,
    settings = AudioRecognizerSettings(
        transcriptionBackend = RecoverableSpeechBackend(OpenWisprTranscriptionBackend(config), failedSpeech, onFailure),
        recordingConfiguration = RecordingSettings(
            preferBluetoothMic = context.getSetting(PREFER_BLUETOOTH),
            requestAudioFocus = context.getSetting(AUDIO_FOCUS),
            canExpandSpace = context.getSetting(CAN_EXPAND_SPACE),
            silenceTimeoutMs = context.getSetting(VAD_SILENCE_TIMEOUT_MS),
        ),
        livePreview = livePreview,
    ),
)
