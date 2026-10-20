package org.futo.inputmethod.latin.openwispr

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.RichInputMethodManager
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.FLOATING_MIC_BUBBLE
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.VAD_SILENCE_TIMEOUT_MS
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingFlow
import org.futo.inputmethod.latin.uix.setSetting
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
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

/** The field's text with a dictation taken back out, or null if it is no longer where it was put. */
fun removeDictation(current: String, start: Int, inserted: String): String? {
    val end = start + inserted.length
    if (inserted.isEmpty() || start < 0 || end > current.length) return null
    if (current.substring(start, end) != inserted) return null
    return current.removeRange(start, end)
}

/**
 * Floating mic bubble for dictating into whatever text field has focus, with the keyboard hidden.
 *
 * Accessibility is what lets it see the focused field in other apps and write into it; its overlay
 * window type needs no separate "display over other apps" grant. It can also keep the soft keyboard
 * from showing, for voice-only typing.
 */
class FloatingMicService : AccessibilityService(), AudioRecognizerListener {
    private enum class Mode { Idle, Recording, Processing }

    private val scope = MainScope()
    private var windowManager: WindowManager? = null
    private var bubble: FloatingMicBubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var spring: Spring? = null
    private var soundPlayer: SoundPlayer? = null

    private var mode = Mode.Idle
    private var recognizer: AudioRecognizer? = null
    private var finished = false
    @Volatile private var failure: String? = null

    /** Field the recording is for; focus can move away while the provider is transcribing. */
    private var target: AccessibilityNodeInfo? = null

    /** Where the last dictation went, so the menu can take it back out. */
    private data class Inserted(val node: AccessibilityNodeInfo, val start: Int, val text: String)
    private var lastInserted: Inserted? = null

    private var bubbleEnabled = true
    /** The user asked to type by voice only; the keyboard stays hidden everywhere but password fields. */
    private var hideKeyboard = false

    private val preferences by lazy { getSharedPreferences("openwispr_floating_mic", Context.MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        soundPlayer = SoundPlayer(this)
        // ModelOutputSanitizer reads the keyboard locale; the keyboard may not have started yet.
        RichInputMethodManager.init(applicationContext)
        hideKeyboard = preferences.getBoolean(KeyHideKeyboard, false)
        bubbleEnabled = getSetting(FLOATING_MIC_BUBBLE)
        scope.launch {
            getSettingFlow(FLOATING_MIC_BUBBLE).collect { enabled ->
                // The keyboard's toggle cannot show a toast of its own (see toast()), so confirm here.
                if (enabled != bubbleEnabled) toast(getString(
                    if (enabled) R.string.floating_mic_turned_on else R.string.floating_mic_turned_off))
                bubbleEnabled = enabled
                if (!enabled) recognizer?.cancel()
                refresh()
            }
        }
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = refresh()

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes the screen edges the bubble is docked to.
        val layout = params ?: return
        removeMenu()
        spring?.stop()
        dock(layout)
        bubble?.let { runCatching { windowManager?.updateViewLayout(it, layout) } }
    }

    override fun onDestroy() {
        // reset, not cancel: the cancel callback would touch windows of a dead service.
        recognizer?.reset()
        runCatching { setKeyboardHidden(false) }
        removeBubble()
        removeMessage()
        soundPlayer?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun focusedEditable(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }

    private fun refresh() {
        // The hidden state must not depend on focus: dropping it whenever no field is focused let
        // the keyboard flash up for a moment on every tap into a field. Passwords never go through
        // the bubble, so the keyboard comes back for them; anywhere else the always-present bubble
        // offers "Show keyboard".
        val passwordFocused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isPassword == true
        setKeyboardHidden(bubbleEnabled && hideKeyboard && !passwordFocused)
        // The bubble stays put whatever has focus, so it is always where the user left it.
        if (bubbleEnabled) showBubble() else removeBubble()
    }

    private fun setKeyboardHidden(hidden: Boolean) {
        val mode = if (hidden) SHOW_MODE_HIDDEN else SHOW_MODE_AUTO
        if (softKeyboardController.showMode == mode) return
        softKeyboardController.setShowMode(mode)
        // The app focused meanwhile thinks the keyboard is already up and ignores taps on the field.
        if (!hidden && findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true) KeyboardBridge.requestShow()
    }

    private fun dp(value: Number): Int = (value.toFloat() * resources.displayMetrics.density).roundToInt()

    /** Screen area clear of the status and navigation bars, in overlay window coordinates. */
    private fun usableArea(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.let { manager ->
                val metrics = manager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                return Rect(insets.left, insets.top,
                    metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom)
            }
        }
        val metrics = resources.displayMetrics
        return Rect(0, dp(24), metrics.widthPixels, metrics.heightPixels - dp(48))
    }

    /** The bubble window's transparent margin minus the gap kept between the disc and the edge. */
    private fun edgeOverhang(): Int =
        (dp(FloatingMicBubbleView.SizeDp) - dp(FloatingMicBubbleView.RadiusDp * 2)) / 2 - dp(EdgeGapDp)

    private fun dockedX(right: Boolean, size: Int): Int {
        val area = usableArea()
        return if (right) area.right - size + edgeOverhang() else area.left - edgeOverhang()
    }

    private fun clampY(y: Int, size: Int): Int {
        val area = usableArea()
        return y.coerceIn(area.top - edgeOverhang(), max(area.top, area.bottom - size + edgeOverhang()))
    }

    /** Snap to the saved side, e.g. after rotation or when the bubble reappears. */
    private fun dock(layout: WindowManager.LayoutParams) {
        layout.x = dockedX(preferences.getBoolean(KeyRight, true), layout.width)
        layout.y = clampY(preferences.getInt(KeyY, usableArea().centerY()), layout.height)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val manager = windowManager ?: return
        val size = dp(FloatingMicBubbleView.SizeDp)
        val layout = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            dock(this)
        }
        val view = FloatingMicBubbleView(this)
        view.contentDescription = getString(R.string.openwispr_floating_mic_title)
        view.setOnTouchListener(DragOrTap(view, layout))
        manager.addView(view, layout)
        bubble = view
        params = layout
        spring = Spring(view, layout)
        view.scaleX = .3f
        view.scaleY = .3f
        view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
            .setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    private fun removeBubble() {
        removeMenu()
        spring?.stop()
        spring = null
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
        if (mode == Mode.Idle) showMenu() else recognizer?.cancel()
    }

    private var menu: View? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        val manager = windowManager ?: return
        val anchor = params ?: return
        removeMenu()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            background = GradientDrawable().apply {
                setColor(0xF51C1B33.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x1FFFFFFF)
            }
            clipToOutline = true
            // Touching anywhere else closes the menu, like a popup.
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) removeMenu()
                false
            }
        }
        fun item(text: Int, action: () -> Unit) = card.addView(TextView(this).apply {
            setText(text)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            minWidth = dp(200)
            setPadding(dp(20), dp(13), dp(24), dp(13))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(0xFFFFFFFF.toInt()))
            setOnClickListener {
                removeMenu()
                action()
            }
        })
        if (lastInserted != null) item(R.string.floating_mic_undo) { undoLastDictation() }
        item(if (hideKeyboard) R.string.floating_mic_show_keyboard else R.string.floating_mic_hide_keyboard) {
            hideKeyboard = !hideKeyboard
            preferences.edit().putBoolean(KeyHideKeyboard, hideKeyboard).apply()
            refresh()
        }
        item(R.string.floating_mic_turn_off) {
            scope.launch { setSetting(FLOATING_MIC_BUBBLE, false) }
        }
        item(R.string.floating_mic_settings) { openVoiceSettings() }

        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val area = usableArea()
        val onRight = anchor.x + anchor.width / 2 > area.centerX()
        // Opens beside the disc, toward the middle of the screen.
        val discGap = (anchor.width - dp(FloatingMicBubbleView.RadiusDp * 2)) / 2 - dp(8)
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (onRight) anchor.x + discGap - card.measuredWidth else anchor.x + anchor.width - discGap)
                .coerceIn(area.left, max(area.left, area.right - card.measuredWidth))
            y = (anchor.y + anchor.height / 2 - card.measuredHeight / 2)
                .coerceIn(area.top, max(area.top, area.bottom - card.measuredHeight))
        }
        runCatching { manager.addView(card, layout) }.onSuccess {
            menu = card
            card.pivotX = if (onRight) card.measuredWidth.toFloat() else 0f
            card.pivotY = (anchor.y + anchor.height / 2 - layout.y).toFloat()
            card.scaleX = .6f
            card.scaleY = .6f
            card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
        }
    }

    private fun removeMenu() {
        menu?.let { runCatching { windowManager?.removeView(it) } }
        menu = null
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
        lastInserted = null
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
        bubble?.state = when (value) {
            Mode.Idle -> FloatingMicBubbleView.State.Idle
            Mode.Recording -> FloatingMicBubbleView.State.Recording
            Mode.Processing -> FloatingMicBubbleView.State.Processing
        }
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
        // An empty field reports its hint as text. Android 7 has no API to tell the two apart, so
        // there the hint may stay in front of the dictation.
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
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
            select(node, cursor, cursor)
            lastInserted = Inserted(node, cursor - text.length, text)
            return
        }
        // Web views and some custom editors refuse SET_TEXT but accept a paste at the cursor.
        copyToClipboard(text)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            lastInserted = Inserted(node, cursor - text.length, text)
        } else {
            toast("This field does not accept dictation. Text copied to clipboard.")
        }
    }

    private fun select(node: AccessibilityNodeInfo, start: Int, end: Int) =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        })

    /** Removes the last dictation, but only if it is still exactly where it was put. */
    private fun undoLastDictation() {
        val last = lastInserted ?: return
        lastInserted = null
        val node = last.node.takeIf { it.refresh() }
        val restored = node?.text?.toString()?.let { removeDictation(it, last.start, last.text) }
        if (node == null || restored == null) {
            toast("The dictated text was edited or moved, so it can't be undone.")
            return
        }
        val replaced = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, restored)
        })
        if (replaced) {
            select(node, last.start, last.start)
            return
        }
        // Editors that took the dictation as a paste: select it and cut it back out.
        if (!select(node, last.start, last.start + last.text.length) || !node.performAction(AccessibilityNodeInfo.ACTION_CUT)) {
            toast("This field does not allow undoing the dictation.")
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
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Touches pass through, so a note over the keyboard never swallows a key press.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = usableArea().top + dp(12)
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

    /**
     * Damped spring that carries the bubble window to its docked spot. It starts with the fling's
     * velocity, so a throw overshoots the edge a little and settles back.
     */
    private inner class Spring(
        private val view: View,
        private val layout: WindowManager.LayoutParams,
    ) : Runnable {
        private var x = 0f
        private var y = 0f
        private var vx = 0f
        private var vy = 0f
        private var targetX = 0f
        private var targetY = 0f
        private var lastFrame = 0L
        private var running = false

        fun animateTo(x: Int, y: Int, velocityX: Float, velocityY: Float) {
            this.x = layout.x.toFloat()
            this.y = layout.y.toFloat()
            vx = velocityX
            vy = velocityY
            targetX = x.toFloat()
            targetY = y.toFloat()
            lastFrame = 0L
            if (!running) {
                running = true
                view.postOnAnimation(this)
            }
        }

        fun stop() {
            running = false
            view.removeCallbacks(this)
        }

        override fun run() {
            if (!running || !view.isAttachedToWindow) {
                running = false
                return
            }
            val now = SystemClock.uptimeMillis()
            var dt = if (lastFrame == 0L) .016f else min(now - lastFrame, 50L) / 1000f
            lastFrame = now
            val damping = 2f * DampingRatio * sqrt(Stiffness)
            // Small fixed steps keep the integration stable when a frame runs long.
            while (dt > 0f) {
                val h = min(dt, .004f)
                vx += (-Stiffness * (x - targetX) - damping * vx) * h
                vy += (-Stiffness * (y - targetY) - damping * vy) * h
                x += vx * h
                y += vy * h
                dt -= h
            }
            val settled = abs(x - targetX) < .5f && abs(y - targetY) < .5f && abs(vx) + abs(vy) < 30f
            layout.x = if (settled) targetX.roundToInt() else x.roundToInt()
            layout.y = if (settled) targetY.roundToInt() else y.roundToInt()
            runCatching { windowManager?.updateViewLayout(view, layout) }
            if (settled) running = false else view.postOnAnimation(this)
        }
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
        private var velocity: VelocityTracker? = null
        private val longPress = Runnable {
            longPressed = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            press(false)
            onLongPress()
        }

        private fun press(down: Boolean) {
            val scale = if (down) .88f else 1f
            view.animate().scaleX(scale).scaleY(scale).setDuration(if (down) 120 else 320)
                .setInterpolator(OvershootInterpolator(if (down) 0f else 3f)).start()
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeMenu()
                    spring?.stop()
                    downX = event.rawX
                    downY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    dragging = false
                    longPressed = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().apply { addMovement(event) }
                    press(true)
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(event)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && !longPressed && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        view.removeCallbacks(longPress)
                        // Lifted while it follows the finger.
                        view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
                    }
                    if (dragging && view.isAttachedToWindow) {
                        layout.x = startX + dx.toInt()
                        layout.y = startY + dy.toInt()
                        runCatching { windowManager?.updateViewLayout(view, layout) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    velocity?.addMovement(event)
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    if (dragging) {
                        press(false)
                        snap(vx, vy)
                    } else if (!longPressed) {
                        press(false)
                        view.performClick()
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    velocity?.recycle()
                    velocity = null
                    press(false)
                    if (dragging) snap(0f, 0f)
                }
            }
            return true
        }

        /** Dock to the edge the bubble was thrown toward, near the height where it was let go. */
        private fun snap(vx: Float, vy: Float) {
            val area = usableArea()
            val projectedCenter = layout.x + layout.width / 2 + vx * .15f
            val right = projectedCenter > area.centerX()
            val y = clampY((layout.y + vy * .08f).toInt(), layout.height)
            preferences.edit().putBoolean(KeyRight, right).putInt(KeyY, y).apply()
            spring?.animateTo(dockedX(right, layout.width), y, vx, vy)
        }
    }

    companion object {
        private const val KeyRight = "docked_right"
        private const val KeyY = "docked_y"
        private const val KeyHideKeyboard = "hide_keyboard"
        private const val EdgeGapDp = 8
        private const val Stiffness = 420f
        private const val DampingRatio = .55f

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, FloatingMicService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
