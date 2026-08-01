package org.futo.inputmethod.latin.uix.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val message: String = "Configure OpenWispr voice input before dictating",
    private val openSettings: Boolean = true,
) : ActionWindow() {
    @Composable
    override fun windowName(): String = stringResource(R.string.action_voice_input_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    onClick = {
                        if (openSettings) {
                            SettingsActivity.openToNavDest(manager.getContext(), "openwisprVoice")
                        }
                    },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ),
        ) {
            Text(
                message,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                textAlign = TextAlign.Center,
            )
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

    private fun loadSettings(): RecognizerViewSettings {
        shouldPlaySounds = context.getSetting(ENABLE_SOUND)
        return RecognizerViewSettings(
            shouldShowInlinePartialResult = false,
            shouldShowVerboseFeedback = false,
            shouldAnimateBubble = context.getSetting(ANIMATE_BUBBLE),
            failureMessage = "Transcription failed. Tap to check OpenWispr provider settings.",
            transcriptionBackend = OpenWisprTranscriptionBackend(config),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = context.getSetting(PREFER_BLUETOOTH),
                requestAudioFocus = context.getSetting(AUDIO_FOCUS),
                canExpandSpace = context.getSetting(CAN_EXPAND_SPACE),
                useVADAutoStop = context.getSetting(USE_VAD_AUTOSTOP),
            ),
        )
    }

    private val recognizerView: MutableState<RecognizerView?> = mutableStateOf(null)
    private val initJob = manager.getLifecycleScope().launch(Dispatchers.Default) {
        yield()
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

    private var inputTransaction = manager.createInputTransaction()

    @Composable
    override fun windowName(): String = stringResource(R.string.action_voice_input_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    onClick = { recognizerView.value?.finish() },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                )
                .semantics(mergeDescendants = true) { traversalIndex = -1.0f },
        ) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                recognizerView.value?.Content()
            }
        }
    }

    override fun close(): CloseResult {
        inputTransaction.cancel()
        runBlocking { initJob.cancelAndJoin() }
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
            inputTransaction.cancel()
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
        wasFinished = true
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
            inputTransaction.commit(sanitized)
            manager.announce(result)
            manager.closeActionWindow()
        }
    }

    override fun partialResult(result: String) {
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
            inputTransaction.updatePartial(sanitized)
        }
    }

    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean = false

    override fun openSettings() {
        SettingsActivity.openToNavDest(context, "openwisprVoice")
    }
}

val VoiceInputAction = Action(
    icon = R.drawable.mic_fill,
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
            config.isConfigured -> {
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
