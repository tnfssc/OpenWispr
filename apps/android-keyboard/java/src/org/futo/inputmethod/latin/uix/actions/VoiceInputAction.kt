package org.futo.inputmethod.latin.uix.actions

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.openwispr.OpenWisprConfig
import org.futo.inputmethod.latin.openwispr.OpenWisprConfigStore
import org.futo.inputmethod.latin.openwispr.OpenWisprTranscriptionBackend
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.OnDeviceSpeechPreview
import org.futo.inputmethod.latin.openwispr.FailedSpeechStore
import org.futo.inputmethod.latin.openwispr.RecoverableSpeechBackend
import org.futo.inputmethod.latin.openwispr.SpeechRecoveryControls
import org.futo.voiceinput.shared.AudioPreview
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_system_voice_input_title,
    simplePressImpl = { manager, _ -> manager.triggerSystemVoiceInput() },
    persistentState = null,
    windowImpl = null,
    shownInEditor = false,
)

class VoiceInputPersistentState(manager: KeyboardManagerForAction) : PersistentActionState {
    val soundPlayer = SoundPlayer(manager.getContext())

    override suspend fun cleanUp() = Unit
    override fun close() = Unit
}

private class OpenWisprNotConfiguredWindow(
    private val manager: KeyboardManagerForAction,
    private val message: String = "Set up OpenWispr voice input to start dictating.",
    private val openSettings: Boolean = true,
) : ActionWindow() {
    @Composable
    override fun windowName(): String = stringResource(R.string.action_voice_input_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, textAlign = TextAlign.Center)
                if (openSettings) {
                    Button(onClick = { SettingsActivity.openToNavDest(manager.getContext(), "openwisprVoice") }) {
                        Text("Open voice setup")
                    }
                }
            }
        }
    }
}

private class VoiceInputActionWindow(
    private val manager: KeyboardManagerForAction,
    private val state: VoiceInputPersistentState,
    private val config: OpenWisprConfig,
) : ActionWindow(), RecognizerViewListener {
    private val context = manager.getContext()
    private var shouldPlaySounds = false
    private var closed = false
    private val failedSpeech = FailedSpeechStore(context.noBackupFilesDir)
    private val recovering = mutableStateOf(failedSpeech.exists())
    private val failure = mutableStateOf<String?>(null)

    private fun localPreview(): AudioPreview? {
        if (Build.VERSION.SDK_INT < 33) return null
        val language = config.language.trim().ifBlank {
            manager.getActiveLocales().firstOrNull()?.toLanguageTag() ?: java.util.Locale.getDefault().toLanguageTag()
        }
        return OnDeviceSpeechPreview(context, manager.getLifecycleScope(), language, ::partialResult)
    }

    private fun loadSettings(): RecognizerViewSettings {
        shouldPlaySounds = context.getSetting(ENABLE_SOUND)
        return RecognizerViewSettings(
            shouldShowInlinePartialResult = false,
            shouldShowVerboseFeedback = false,
            shouldAnimateBubble = context.getSetting(ANIMATE_BUBBLE),
            failureMessage = "Transcription failed. Tap to check OpenWispr provider settings.",
            transcriptionBackend = RecoverableSpeechBackend(OpenWisprTranscriptionBackend(config), failedSpeech) {
                failure.value = it
            },
            livePreview = localPreview(),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = context.getSetting(PREFER_BLUETOOTH),
                requestAudioFocus = context.getSetting(AUDIO_FOCUS),
                canExpandSpace = context.getSetting(CAN_EXPAND_SPACE),
                useVADAutoStop = context.getSetting(USE_VAD_AUTOSTOP),
            ),
        )
    }

    private val recognizerView: MutableState<RecognizerView?> = mutableStateOf(null)
    private val initJob = manager.getLifecycleScope().launch(Dispatchers.Main) {
        yield()
        if (closed) return@launch
        recovering.value = failedSpeech.exists()
        if (recovering.value) { inputTransaction?.discardPartial(); return@launch }
        val view = RecognizerView(
            context = context,
            listener = this@VoiceInputActionWindow,
            settings = loadSettings(),
            lifecycleScope = manager.getLifecycleScope(),
        )
        recognizerView.value = view
        view.reset()
        view.start()
    }

    private val inputTransaction = if (recovering.value) null else manager.createInputTransaction()

    @Composable
    override fun windowName(): String = stringResource(R.string.action_voice_input_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { traversalIndex = -1.0f },
        ) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                if (recovering.value) {
                    SpeechRecoveryControls(
                        store = failedSpeech,
                        onResolved = { manager.closeActionWindow() },
                        onSettings = ::openSettings,
                        onInsert = if (manager.getCurrentInputEditorInfo() == null) null else { text ->
                            // Resolve the currently active input only when the user chooses Insert.
                            val current = manager.createInputTransaction()
                            current.commit(ModelOutputSanitizer.sanitize(text, current.textContext))
                        },
                    )
                } else if (failure.value != null) {
                    Text(failure.value!!, Modifier.padding(16.dp))
                } else {
                    recognizerView.value?.Content()
                }
            }
        }
    }

    override fun close(): CloseResult {
        closed = true
        inputTransaction?.discardPartial()
        initJob.cancel()
        recognizerView.value?.cancel()
        return CloseResult.Default
    }

    private var wasFinished = false
    private var cancelPlayed = false

    override fun cancelled() {
        if (!wasFinished) {
            if (shouldPlaySounds && !cancelPlayed) {
                state.soundPlayer.playCancelSound()
                cancelPlayed = true
            }
            inputTransaction?.discardPartial()
            recovering.value = failedSpeech.exists()
        }
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        if (shouldPlaySounds) state.soundPlayer.playStartSound()
        if (device.bluetoothAvailable) {
            manager.getLifecycleScope().launch {
                context.setSetting(PREFER_BLUETOOTH, device.bluetoothActive)
            }
        }
    }

    override fun finished(result: String) {
        if (closed || wasFinished) return
        wasFinished = true
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            if (closed) return@launch
            val transaction = inputTransaction ?: return@launch
            val sanitized = ModelOutputSanitizer.sanitize(result, transaction.textContext)
            if (sanitized.isBlank()) transaction.discardPartial() else transaction.commit(sanitized)
            manager.announce(result)
            manager.closeActionWindow()
        }
    }

    override fun partialResult(result: String) {
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            if (closed || wasFinished) return@launch
            val transaction = inputTransaction ?: return@launch
            val sanitized = ModelOutputSanitizer.sanitize(result, transaction.textContext)
            transaction.updatePartial(sanitized)
        }
    }

    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean = false

    override fun openSettings() {
        SettingsActivity.openToNavDest(context, "openwisprVoice")
    }
}

val VoiceInputAction = Action(
    icon = R.drawable.openwispr_logo,
    tintIcon = false,
    name = R.string.action_voice_input_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    persistentState = { VoiceInputPersistentState(it) },
    windowImpl = { manager, persistentState ->
        val config = OpenWisprConfigStore.load(manager.getContext())
        when {
            manager.isDeviceLocked() -> OpenWisprNotConfiguredWindow(
                manager,
                message = "Voice input is unavailable while device is locked",
                openSettings = false,
            )
            config.isConfigured || FailedSpeechStore(manager.getContext().noBackupFilesDir).exists() -> {
            VoiceInputActionWindow(
                manager = manager,
                state = persistentState as VoiceInputPersistentState,
                config = config,
            )
            }
            else -> OpenWisprNotConfiguredWindow(manager)
        }
    },
)
