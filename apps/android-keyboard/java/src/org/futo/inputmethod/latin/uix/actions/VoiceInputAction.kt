package org.futo.inputmethod.latin.uix.actions

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.openwispr.FailedSpeechStore
import org.futo.inputmethod.latin.openwispr.OpenWisprConfig
import org.futo.inputmethod.latin.openwispr.OpenWisprConfigStore
import org.futo.inputmethod.latin.openwispr.OpenWisprTranscriptionBackend
import org.futo.inputmethod.latin.openwispr.RecoverableSpeechBackend
import org.futo.inputmethod.latin.openwispr.SpeechRecoveryControls
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionInputTransaction
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.VAD_SILENCE_TIMEOUT_MS
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.voiceinput.shared.AudioPreview
import org.futo.voiceinput.shared.AudioRecognizer
import org.futo.voiceinput.shared.AudioRecognizerSettings
import org.futo.voiceinput.shared.OnDeviceSpeechPreview
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_system_voice_input_title,
    simplePressImpl = { manager, _ -> manager.triggerSystemVoiceInput() },
    persistentState = null,
    windowImpl = null,
    shownInEditor = false,
)

/**
 * Dictation in place: the voice key itself shows that it is listening, and nothing else on screen
 * moves. Tap to start, tap again (or press any key) to stop and insert.
 */
object InlineDictation {
    enum class Phase { Idle, Recording, Processing }

    /** Read by the voice key's icon. */
    val phase = mutableStateOf(Phase.Idle)
    val level = mutableFloatStateOf(0f)

    /** Failure to explain when the voice panel opens after a dictation could not be transcribed. */
    internal var pendingFailure: String? = null

    private var session: Session? = null
    private var soundPlayer: SoundPlayer? = null

    fun toggle(manager: KeyboardManagerForAction) {
        session?.let {
            it.finish()
            return
        }
        val context = manager.getContext()
        val config = OpenWisprConfigStore.load(context)
        // Locked devices, setup and recovering a failed recording need room for an explanation.
        if (manager.isDeviceLocked() || !config.isConfigured || FailedSpeechStore(context.noBackupFilesDir).exists()) {
            manager.activateAction(VoiceInputPanelAction)
            return
        }
        val sounds = soundPlayer ?: SoundPlayer(context.applicationContext).also { soundPlayer = it }
        session = Session(manager, config, sounds).also { it.start() }
    }

    /** A key typed mid-dictation can't reach the field, so it means "done talking" instead. */
    fun onKeyPressed() {
        session?.finish()
    }

    /** The field or keyboard went away; the transaction is no longer valid. */
    fun cancel() {
        session?.cancel()
    }

    private class Session(
        private val manager: KeyboardManagerForAction,
        config: OpenWisprConfig,
        private val sounds: SoundPlayer,
    ) : AudioRecognizerListener {
        private val context = manager.getContext()
        private val failedSpeech = FailedSpeechStore(context.noBackupFilesDir)
        private val playSounds = context.getSetting(ENABLE_SOUND)
        private val transaction: ActionInputTransaction = manager.createInputTransaction()
        private var failure: String? = null
        private var done = false

        private val recognizer = AudioRecognizer(
            context = context,
            lifecycleScope = manager.getLifecycleScope(),
            listener = this,
            settings = AudioRecognizerSettings(
                transcriptionBackend = RecoverableSpeechBackend(OpenWisprTranscriptionBackend(config), failedSpeech) {
                    failure = it
                },
                recordingConfiguration = RecordingSettings(
                    preferBluetoothMic = context.getSetting(PREFER_BLUETOOTH),
                    requestAudioFocus = context.getSetting(AUDIO_FOCUS),
                    canExpandSpace = context.getSetting(CAN_EXPAND_SPACE),
                    silenceTimeoutMs = context.getSetting(VAD_SILENCE_TIMEOUT_MS),
                ),
                livePreview = localPreview(config),
            ),
        )

        private fun localPreview(config: OpenWisprConfig): AudioPreview? {
            if (Build.VERSION.SDK_INT < 33) return null
            val language = config.language.trim().ifBlank {
                manager.getActiveLocales().firstOrNull()?.toLanguageTag() ?: java.util.Locale.getDefault().toLanguageTag()
            }
            return OnDeviceSpeechPreview(context, manager.getLifecycleScope(), language, ::partialResult)
        }

        fun start() {
            // Show the listening state at once; the mic takes a moment to open.
            phase.value = Phase.Recording
            level.floatValue = 0f
            manager.keepScreenAwake(true)
            recognizer.start()
        }

        fun finish() = recognizer.finish()

        fun cancel() = recognizer.cancel()

        private fun end() {
            done = true
            phase.value = Phase.Idle
            level.floatValue = 0f
            manager.keepScreenAwake(false)
            if (session === this) session = null
        }

        override fun recordingStarted(device: MicrophoneDeviceState) {
            if (playSounds) sounds.playStartSound()
            if (device.bluetoothAvailable) {
                manager.getLifecycleScope().launch { context.setSetting(PREFER_BLUETOOTH, device.bluetoothActive) }
            }
        }

        override fun updateMagnitude(magnitude: Float, state: MagnitudeState) {
            level.floatValue = magnitude
        }

        override fun processing() {
            phase.value = Phase.Processing
        }

        override fun partialResult(result: String) {
            if (done) return
            transaction.updatePartial(ModelOutputSanitizer.sanitize(result, transaction.textContext))
        }

        override fun finished(result: String) {
            if (done) return
            val sanitized = ModelOutputSanitizer.sanitize(result, transaction.textContext)
            if (sanitized.isBlank()) transaction.discardPartial() else transaction.commit(sanitized)
            manager.announce(result)
            end()
        }

        override fun cancelled() {
            if (done) return
            if (playSounds) sounds.playCancelSound()
            transaction.discardPartial()
            end()
        }

        override fun modelLoadingFailed() {
            if (done) return
            transaction.discardPartial()
            end()
            // The audio was kept; the panel offers retry, copy or discard.
            pendingFailure = failure
            manager.activateAction(VoiceInputPanelAction)
        }

        override fun needPermission(onResult: (Boolean) -> Unit) {
            context.startActivity(Intent().setClassName(context, "org.futo.inputmethod.latin.MicPermissionActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onResult(false)
            if (!done) {
                transaction.discardPartial()
                end()
            }
        }

        override fun loading() = Unit
        override fun decodingStatus(status: InferenceState) = Unit
        override fun languageDetected(language: Language) = Unit
    }
}

private class OpenWisprMessageWindow(
    private val manager: KeyboardManagerForAction,
    private val message: String,
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

private class SpeechRecoveryWindow(private val manager: KeyboardManagerForAction) : ActionWindow() {
    private val failedSpeech = FailedSpeechStore(manager.getContext().noBackupFilesDir)
    private val failure = InlineDictation.pendingFailure.also { InlineDictation.pendingFailure = null }

    @Composable
    override fun windowName(): String = stringResource(R.string.action_voice_input_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                failure?.let { Text(it, Modifier.padding(horizontal = 16.dp), textAlign = TextAlign.Center) }
                SpeechRecoveryControls(
                    store = failedSpeech,
                    onResolved = { manager.closeActionWindow() },
                    onSettings = { SettingsActivity.openToNavDest(manager.getContext(), "openwisprVoice") },
                    onInsert = if (manager.getCurrentInputEditorInfo() == null) null else { text ->
                        // Resolve the currently active input only when the user chooses Insert.
                        val current = manager.createInputTransaction()
                        current.commit(ModelOutputSanitizer.sanitize(text, current.textContext))
                    },
                )
            }
        }
    }
}

/** Full-height voice panel, only for what can't be shown on the key: setup, lock and recovery. */
val VoiceInputPanelAction = Action(
    icon = R.drawable.openwispr_glyph,
    tintIcon = false,
    name = R.string.action_voice_input_panel_title,
    simplePressImpl = null,
    persistentState = null,
    shownInEditor = false,
    windowImpl = { manager, _ ->
        val context = manager.getContext()
        when {
            manager.isDeviceLocked() -> OpenWisprMessageWindow(
                manager,
                message = "Voice input is unavailable while device is locked",
                openSettings = false,
            )
            FailedSpeechStore(context.noBackupFilesDir).exists() -> SpeechRecoveryWindow(manager)
            !OpenWisprConfigStore.load(context).isConfigured ->
                OpenWisprMessageWindow(manager, "Set up OpenWispr voice input to start dictating.")
            else -> OpenWisprMessageWindow(
                manager,
                InlineDictation.pendingFailure.also { InlineDictation.pendingFailure = null }
                    ?: "Transcription failed. Check OpenWispr provider settings.",
            )
        }
    },
)

val VoiceInputAction = Action(
    icon = R.drawable.openwispr_glyph,
    tintIcon = false,
    name = R.string.action_voice_input_title,
    simplePressImpl = { manager, _ -> InlineDictation.toggle(manager) },
    windowImpl = null,
    iconContent = { modifier -> VoiceKeyIcon(modifier) },
)
