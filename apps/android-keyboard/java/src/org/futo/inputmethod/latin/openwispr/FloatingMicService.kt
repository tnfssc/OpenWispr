package org.futo.inputmethod.latin.openwispr

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.RichInputMethodManager
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.VAD_SILENCE_TIMEOUT_MS
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.inputmethod.latin.uix.utils.TextContext
import org.futo.voiceinput.shared.AudioRecognizer
import org.futo.voiceinput.shared.AudioRecognizerSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DictationSplice(val newText: String, val cursor: Int, val inserted: String)

/**
 * Put dictation at the field's cursor, replacing any selection. A missing or stale selection
 * (reported as -1 or past the end) appends. Returns null when there is nothing to insert.
 */
fun spliceDictation(
    current: String,
    selectionStart: Int,
    selectionEnd: Int,
    result: String,
    sanitize: (before: String, after: String) -> String,
): DictationSplice? {
    var start = min(selectionStart, selectionEnd)
    var end = max(selectionStart, selectionEnd)
    if (start < 0 || end > current.length) {
        start = current.length
        end = current.length
    }
    if (result.isBlank()) return null
    val before = current.substring(0, start)
    val after = current.substring(end)
    val text = sanitize(before, after)
    if (text.isBlank()) return null
    return DictationSplice(before + text + after, start + text.length, text)
}

/**
 * Floating mic bubble for dictating into whatever text field has focus, with the keyboard hidden.
 *
 * Accessibility is what lets it see the focused field in other apps and write into it; its overlay
 * window type needs no separate "display over other apps" grant.
 */
class FloatingMicService : AccessibilityService(), AudioRecognizerListener {
    private enum class Mode { Idle, Recording, Processing }

    private val scope = MainScope()
    private var windowManager: WindowManager? = null
    private var bubble: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var soundPlayer: SoundPlayer? = null

    private var mode = Mode.Idle
    private var recognizer: AudioRecognizer? = null
    private var finished = false
    @Volatile private var failure: String? = null

    /** Field the recording is for; focus can move away while the provider is transcribing. */
    private var target: AccessibilityNodeInfo? = null
    /** Field the user dismissed the bubble for; it stays hidden until focus moves elsewhere. */
    private var dismissedFor: AccessibilityNodeInfo? = null

    private val preferences by lazy { getSharedPreferences("openwispr_floating_mic", Context.MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        soundPlayer = SoundPlayer(this)
        // ModelOutputSanitizer reads the keyboard locale; the keyboard may not have started yet.
        RichInputMethodManager.init(applicationContext)
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = refresh()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        // reset, not cancel: the cancel callback would touch windows of a dead service.
        recognizer?.reset()
        removeBubble()
        removeMessage()
        soundPlayer?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun focusedEditable(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }

    private fun keyboardVisible(): Boolean =
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun refresh() {
        if (mode != Mode.Idle) return
        val field = focusedEditable()
        if (field != dismissedFor) dismissedFor = null
        // The keyboard has its own mic; the bubble is for when the keyboard is out of the way.
        if (field != null && dismissedFor == null && !keyboardVisible()) showBubble() else removeBubble()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val manager = windowManager ?: return
        val size = dp(BubbleSizeDp)
        val layout = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = resources.displayMetrics
            x = preferences.getInt("x", metrics.widthPixels - size - dp(8))
            y = preferences.getInt("y", metrics.heightPixels / 2)
            clampToScreen(this)
        }
        val view = BubbleView(this)
        view.contentDescription = getString(R.string.openwispr_floating_mic_title)
        view.setOnTouchListener(DragOrTap(view, layout))
        manager.addView(view, layout)
        bubble = view
        params = layout
    }

    /** Saved positions can fall off-screen after rotation or a display-size change. */
    private fun clampToScreen(layout: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        layout.x = layout.x.coerceIn(0, max(0, metrics.widthPixels - layout.width))
        layout.y = layout.y.coerceIn(0, max(0, metrics.heightPixels - layout.height))
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
        params = null
    }

    private fun onTap() {
        when (mode) {
            Mode.Idle -> startDictation()
            Mode.Recording -> recognizer?.finish()
            Mode.Processing -> Unit
        }
    }

    private fun onLongPress() {
        if (mode == Mode.Idle) {
            dismissedFor = focusedEditable()
            removeBubble()
        } else {
            recognizer?.cancel()
        }
    }

    private fun startDictation() {
        val config = OpenWisprConfigStore.load(this)
        val failedSpeech = FailedSpeechStore(noBackupFilesDir)
        when {
            failedSpeech.exists() -> {
                toast("A failed recording is waiting. Retry or discard it in voice settings first.")
                openVoiceSettings()
                return
            }
            !config.isConfigured -> {
                toast("Set up OpenWispr voice input to start dictating.")
                openVoiceSettings()
                return
            }
        }
        target = focusedEditable()
        finished = false
        failure = null
        val recognizer = AudioRecognizer(
            context = this,
            lifecycleScope = scope,
            listener = this,
            settings = AudioRecognizerSettings(
                transcriptionBackend = RecoverableSpeechBackend(OpenWisprTranscriptionBackend(config), failedSpeech) {
                    failure = it
                },
                recordingConfiguration = RecordingSettings(
                    preferBluetoothMic = getSetting(PREFER_BLUETOOTH),
                    requestAudioFocus = getSetting(AUDIO_FOCUS),
                    canExpandSpace = getSetting(CAN_EXPAND_SPACE),
                    silenceTimeoutMs = getSetting(VAD_SILENCE_TIMEOUT_MS),
                ),
            ),
        )
        this.recognizer = recognizer
        recognizer.start()
    }

    private fun setMode(value: Mode) {
        mode = value
        bubble?.mode = value
        // The system mutes the mic of accessibility services while the screen is off.
        params?.let { layout ->
            layout.flags = if (value == Mode.Idle) layout.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                else layout.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            bubble?.let { runCatching { windowManager?.updateViewLayout(it, layout) } }
        }
        if (value == Mode.Idle) {
            recognizer = null
            target = null
            refresh()
        }
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        if (getSetting(ENABLE_SOUND)) soundPlayer?.playStartSound()
        setMode(Mode.Recording)
    }

    override fun updateMagnitude(magnitude: Float, state: MagnitudeState) {
        bubble?.level = magnitude
    }

    override fun processing() = setMode(Mode.Processing)

    override fun finished(result: String) {
        if (finished) return
        finished = true
        insert(result)
        setMode(Mode.Idle)
    }

    override fun cancelled() {
        if (!finished && getSetting(ENABLE_SOUND)) soundPlayer?.playCancelSound()
        finished = true
        setMode(Mode.Idle)
    }

    override fun modelLoadingFailed() {
        toast(failure ?: "Transcription failed. Check OpenWispr voice settings.")
        finished = true
        setMode(Mode.Idle)
    }

    override fun needPermission(onResult: (Boolean) -> Unit) {
        startActivity(Intent().setClassName(this, "org.futo.inputmethod.latin.MicPermissionActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onResult(false)
        finished = true
        setMode(Mode.Idle)
    }

    override fun loading() = Unit
    override fun partialResult(result: String) = Unit
    override fun decodingStatus(status: InferenceState) = Unit
    override fun languageDetected(language: Language) = Unit

    private fun insert(result: String) {
        if (result.isBlank()) return
        val node = focusedEditable() ?: target?.takeIf { it.refresh() && it.isEditable }
        if (node == null) {
            copyToClipboard(result.trim())
            toast("No text field is focused. Dictation copied to clipboard.")
            return
        }
        // Before API 26 an empty field reports its hint as text, indistinguishable from real text
        // except by matching the hint.
        val showingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.isShowingHintText
            else node.hintText?.toString()?.let { it.isNotEmpty() && it == node.text?.toString() } == true
        val splice = spliceDictation(
            current = if (showingHint) "" else node.text?.toString().orEmpty(),
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
            result = result,
        ) { before, after ->
            runCatching { ModelOutputSanitizer.sanitize(result, TextContext(before, after)) }.getOrElse { result.trim() }
        } ?: return
        val (newText, cursor, text) = splice

        val replaced = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        })
        if (replaced) {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            })
            return
        }
        // Web views and some custom editors refuse SET_TEXT but accept a paste at the cursor.
        copyToClipboard(text)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            toast("This field does not accept dictation. Text copied to clipboard.")
        }
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("OpenWispr dictation", text))
    }

    private fun openVoiceSettings() = SettingsActivity.openToNavDest(this, "openwisprVoice")

    private var message: TextView? = null
    private val hideMessage = Runnable { removeMessage() }

    /**
     * Toasts from a background app are dropped while its notifications are off (the default on
     * Android 13+), so the bubble shows its own short-lived note in the accessibility overlay.
     */
    private fun toast(text: String) {
        val manager = windowManager ?: return
        removeMessage()
        val view = TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xE6202124.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { removeMessage() }
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        runCatching { manager.addView(view, layout) }.onSuccess {
            message = view
            view.postDelayed(hideMessage, 5000)
        }
    }

    private fun removeMessage() {
        message?.let {
            it.removeCallbacks(hideMessage)
            runCatching { windowManager?.removeView(it) }
        }
        message = null
    }

    private inner class DragOrTap(
        private val view: View,
        private val layout: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    dragging = false
                    longPressed = false
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        view.removeCallbacks(longPress)
                    }
                    if (dragging && !longPressed && view.isAttachedToWindow) {
                        layout.x = startX + dx.toInt()
                        layout.y = startY + dy.toInt()
                        clampToScreen(layout)
                        windowManager?.updateViewLayout(view, layout)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (dragging) {
                        preferences.edit().putInt("x", layout.x).putInt("y", layout.y).apply()
                    } else if (!longPressed) {
                        view.performClick()
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> view.removeCallbacks(longPress)
            }
            return true
        }
    }

    private class BubbleView(context: Context) : View(context) {
        var mode = Mode.Idle
            set(value) { field = value; level = 0f; invalidate() }
        var level = 0f
            set(value) { field = value; invalidate() }

        private val density = context.resources.displayMetrics.density
        private val logo = AppCompatResources.getDrawable(context, R.drawable.openwispr_logo)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0FFFFFF.toInt() }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val arc = RectF()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - 8f * density
            canvas.drawCircle(cx, cy + density, radius + density, shadow)
            canvas.drawCircle(cx, cy, radius, fill)

            val inset = (radius * 0.45f).toInt()
            logo?.setBounds((cx - radius).toInt() + inset, (cy - radius).toInt() + inset,
                (cx + radius).toInt() - inset, (cy + radius).toInt() - inset)
            logo?.draw(canvas)

            when (mode) {
                Mode.Idle -> Unit
                Mode.Recording -> {
                    ring.color = 0xFFF23C91.toInt()
                    canvas.drawCircle(cx, cy, radius + (2f + 5f * level.coerceIn(0f, 1f)) * density, ring)
                }
                Mode.Processing -> {
                    ring.color = 0xFF3024ED.toInt()
                    val r = radius + 3f * density
                    arc.set(cx - r, cy - r, cx + r, cy + r)
                    val sweepStart = (SystemClock.uptimeMillis() % 1000L) * 0.36f
                    canvas.drawArc(arc, sweepStart, 90f, false, ring)
                    postInvalidateOnAnimation()
                }
            }
        }
    }

    companion object {
        private const val BubbleSizeDp = 72

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, FloatingMicService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
