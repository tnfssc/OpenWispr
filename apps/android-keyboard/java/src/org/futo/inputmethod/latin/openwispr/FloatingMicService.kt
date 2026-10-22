package org.futo.inputmethod.latin.openwispr

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.openwispr.DictationPhase
import app.openwispr.FailedSpeechStore
import app.openwispr.FieldWriter
import app.openwispr.FloatingBubble
import app.openwispr.KeyboardBridge
import app.openwispr.OpenWisprConfig
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.RichInputMethodManager
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingFlow
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.inputmethod.latin.uix.utils.TextContext
import org.futo.voiceinput.shared.AudioRecognizer
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

/**
 * Floating mic bubble for dictating into whatever text field has focus, with the keyboard hidden.
 *
 * Accessibility is what lets it see the focused field in other apps and write into it. It can also
 * keep the soft keyboard from showing, for voice-only typing.
 */
class FloatingMicService : AccessibilityService(), AudioRecognizerListener {
    private val scope = MainScope()
    private var bubble: FloatingBubble? = null
    private val field = FieldWriter { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
    private var soundPlayer: SoundPlayer? = null

    private var phase = DictationPhase.Idle
    private var recognizer: AudioRecognizer? = null
    private var finished = false
    /**
     * The mic was opened on touch-down, before the touch is known to be a tap. Until the tap the
     * bubble shows nothing, and a drag or long press drops the recording without a sound.
     */
    private var armed = false
    private var micStarted = false
    @Volatile private var failure: String? = null

    /** Field the recording is for; focus can move away while the provider is transcribing. */
    private var target: AccessibilityNodeInfo? = null

    private var bubbleEnabled = true
    /** The user asked to type by voice only; the keyboard stays hidden everywhere but password fields. */
    private var hideKeyboard = false

    private val preferences by lazy { getSharedPreferences("openwispr_floating_mic", Context.MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        soundPlayer = SoundPlayer(this)
        bubble = FloatingBubble(this, getString(R.string.openwispr_floating_mic_title), ::onTap, ::onLongPress,
            ::onTouchDown, ::onTouchAbandoned)
        // ModelOutputSanitizer reads the keyboard locale; the keyboard may not have started yet.
        RichInputMethodManager.init(applicationContext)
        hideKeyboard = preferences.getBoolean(KeyHideKeyboard, false)
        bubbleEnabled = getSetting(FLOATING_MIC_BUBBLE)
        scope.launch {
            getSettingFlow(FLOATING_MIC_BUBBLE).collect { enabled ->
                // The keyboard's toggle cannot show a toast of its own (see FloatingBubble.note), so confirm here.
                if (enabled != bubbleEnabled) note(getString(
                    if (enabled) R.string.floating_mic_turned_on else R.string.floating_mic_turned_off))
                bubbleEnabled = enabled
                if (!enabled) recognizer?.cancel()
                refresh()
            }
        }
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && field.onAccessibilityEvent(event)) return
        refresh()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.redock()
    }

    override fun onDestroy() {
        // reset, not cancel: the cancel callback would touch windows of a dead service.
        recognizer?.reset()
        runCatching { setKeyboardHidden(false) }
        bubble?.destroy()
        soundPlayer?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        // The hidden state must not depend on focus: dropping it whenever no field is focused let
        // the keyboard flash up for a moment on every tap into a field. Passwords never go through
        // the bubble, so the keyboard comes back for them; anywhere else the always-present bubble
        // offers "Show keyboard".
        val passwordFocused = field.focusedField()?.isPassword == true
        setKeyboardHidden(bubbleEnabled && hideKeyboard && !passwordFocused)
        // The bubble stays put whatever has focus, so it is always where the user left it.
        if (bubbleEnabled) bubble?.show() else bubble?.remove()
    }

    private fun setKeyboardHidden(hidden: Boolean) {
        val mode = if (hidden) SHOW_MODE_HIDDEN else SHOW_MODE_AUTO
        if (softKeyboardController.showMode == mode) return
        softKeyboardController.setShowMode(mode)
        // The app focused meanwhile thinks the keyboard is already up and ignores taps on the field.
        if (!hidden && field.focusedField() != null) KeyboardBridge.requestShow()
    }

    /** Opening the mic takes a moment, so start it as the finger lands rather than when it lifts. */
    private fun onTouchDown() {
        if (phase != DictationPhase.Idle || recognizer != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val config = OpenWisprConfigStore.load(this)
        val failedSpeech = FailedSpeechStore(noBackupFilesDir)
        if (failedSpeech.exists() || !config.isConfigured) return
        armed = true
        record(config, failedSpeech)
    }

    private fun onTouchAbandoned() {
        if (!armed) return
        recognizer?.cancel()
    }

    private fun onTap() {
        if (armed) {
            armed = false
            if (micStarted) showRecording()
            return
        }
        when (phase) {
            DictationPhase.Idle -> startDictation()
            DictationPhase.Recording -> recognizer?.finish()
            DictationPhase.Processing -> Unit
        }
    }

    private fun onLongPress() {
        if (phase != DictationPhase.Idle) {
            recognizer?.cancel()
            return
        }
        bubble?.showMenu(listOfNotNull(
            if (field.canUndo) getString(R.string.floating_mic_undo) to ::undoLastDictation else null,
            getString(if (hideKeyboard) R.string.floating_mic_show_keyboard else R.string.floating_mic_hide_keyboard) to {
                hideKeyboard = !hideKeyboard
                preferences.edit().putBoolean(KeyHideKeyboard, hideKeyboard).apply()
                refresh()
            },
            getString(R.string.floating_mic_turn_off) to { scope.launch { setSetting(FLOATING_MIC_BUBBLE, false) }; Unit },
            getString(R.string.floating_mic_settings) to ::openVoiceSettings,
        ))
    }

    private fun startDictation() {
        val config = OpenWisprConfigStore.load(this)
        val failedSpeech = FailedSpeechStore(noBackupFilesDir)
        val blocker = when {
            failedSpeech.exists() -> "A failed recording is waiting. Retry or discard it in voice settings first."
            !config.isConfigured -> "Set up OpenWispr voice input to start dictating."
            else -> null
        }
        if (blocker != null) {
            note(blocker)
            openVoiceSettings()
            return
        }
        record(config, failedSpeech)
    }

    private fun record(config: OpenWisprConfig, failedSpeech: FailedSpeechStore) {
        target = field.focusedEditable()
        finished = false
        micStarted = false
        failure = null
        recognizer = openWisprRecognizer(this, scope, this, config, failedSpeech) { failure = it }
            .also { it.start() }
    }

    private fun showRecording() {
        // Not on touch-down: a long press must still offer to undo the previous dictation.
        field.forgetLast()
        val sound = getSetting(ENABLE_SOUND)
        if (sound) soundPlayer?.playStartSound()
        recognizer?.startSoundPlayed(sound)
        setPhase(DictationPhase.Recording)
    }

    private fun setPhase(value: DictationPhase) {
        phase = value
        bubble?.phase = value
        if (value == DictationPhase.Idle) {
            recognizer = null
            armed = false
            target = null
            refresh()
        }
    }

    /** Ends the recording once, whichever callback comes first. */
    private fun end() {
        finished = true
        setPhase(DictationPhase.Idle)
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        micStarted = true
        if (!armed) showRecording()
    }

    override fun updateMagnitude(magnitude: Float, state: MagnitudeState) {
        if (!armed) bubble?.level = magnitude
    }

    override fun processing() = setPhase(DictationPhase.Processing)

    override fun finished(result: String) {
        if (finished) return
        insert(result)
        end()
    }

    override fun cancelled() {
        if (!finished && !armed && getSetting(ENABLE_SOUND)) soundPlayer?.playCancelSound()
        end()
    }

    override fun modelLoadingFailed() {
        note(failure ?: "Transcription failed. Check OpenWispr voice settings.")
        end()
    }

    override fun needPermission(onResult: (Boolean) -> Unit) {
        startActivity(Intent().setClassName(this, "org.futo.inputmethod.latin.MicPermissionActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onResult(false)
        end()
    }

    override fun loading() = Unit
    override fun partialResult(result: String) = Unit
    override fun decodingStatus(status: InferenceState) = Unit
    override fun languageDetected(language: Language) = Unit

    private fun insert(result: String) {
        val outcome = field.insert(result, target, sanitize = { before, after ->
            runCatching { ModelOutputSanitizer.sanitize(result, TextContext(before, after)) }.getOrElse { result.trim() }
        }, paste = ::copyToClipboard)
        when (outcome) {
            FieldWriter.Result.NoField -> {
                copyToClipboard(result.trim())
                note("No text field is focused. Dictation copied to clipboard.")
            }
            FieldWriter.Result.Refused -> note("This field does not accept dictation. Text copied to clipboard.")
            else -> Unit
        }
    }

    private fun undoLastDictation() {
        if (!field.undo()) note("The dictated text was edited or moved, so it can't be undone.")
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("OpenWispr dictation", text))
    }

    private fun note(text: String) {
        bubble?.note(text)
    }

    private fun openVoiceSettings() = SettingsActivity.openToNavDest(this, "openwisprVoice")

    companion object {
        private const val KeyHideKeyboard = "hide_keyboard"

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, FloatingMicService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
