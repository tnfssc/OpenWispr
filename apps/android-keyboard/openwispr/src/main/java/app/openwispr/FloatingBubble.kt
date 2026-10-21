package app.openwispr

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The floating mic's windows in an accessibility overlay: the bubble, docked to a screen edge with
 * a springy snap; its long-press menu; and short notes. The overlay window type needs no separate
 * "display over other apps" grant.
 */
class FloatingBubble(
    private val context: Context,
    private val description: String,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("openwispr_floating_mic", Context.MODE_PRIVATE)
    private var bubble: FloatingMicBubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var spring: Spring? = null
    private var menu: View? = null
    private var note: TextView? = null
    private val hideNote = Runnable { removeNote() }

    var phase: DictationPhase
        get() = bubble?.state ?: DictationPhase.Idle
        set(value) {
            bubble?.state = value
            // The system mutes the mic of accessibility services while the screen is off.
            val layout = params ?: return
            layout.flags = if (value == DictationPhase.Idle) layout.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                else layout.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            update(bubble, layout)
        }

    var level: Float
        get() = bubble?.level ?: 0f
        set(value) {
            bubble?.level = value
        }

    private fun dp(value: Number): Int = (value.toFloat() * context.resources.displayMetrics.density).roundToInt()

    private fun update(view: View?, layout: WindowManager.LayoutParams) {
        view?.let { runCatching { windowManager.updateViewLayout(it, layout) } }
    }

    private fun overlay(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or flags,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Screen area clear of the status and navigation bars, in overlay window coordinates. */
    private fun usableArea(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(insets.left, insets.top,
                metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom)
        }
        val metrics = context.resources.displayMetrics
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

    /** Rotation changes the screen edges the bubble is docked to. */
    fun redock() {
        val layout = params ?: return
        removeMenu()
        spring?.stop()
        dock(layout)
        update(bubble, layout)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubble != null) return
        val size = dp(FloatingMicBubbleView.SizeDp)
        val layout = overlay(size, size, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply { dock(this) }
        val view = FloatingMicBubbleView(context)
        view.contentDescription = description
        view.setOnTouchListener(DragOrTap(view, layout))
        windowManager.addView(view, layout)
        bubble = view
        params = layout
        spring = Spring(view, layout)
        view.scaleX = .3f
        view.scaleY = .3f
        view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
            .setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    fun remove() {
        removeMenu()
        spring?.stop()
        spring = null
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        params = null
    }

    fun destroy() {
        remove()
        removeNote()
    }

    /** A card beside the disc, opening toward the middle of the screen. */
    @SuppressLint("ClickableViewAccessibility")
    fun showMenu(items: List<Pair<String, () -> Unit>>) {
        val anchor = params ?: return
        removeMenu()
        val card = LinearLayout(context).apply {
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
        for ((label, action) in items) card.addView(TextView(context).apply {
            text = label
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

        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val area = usableArea()
        val onRight = anchor.x + anchor.width / 2 > area.centerX()
        val discGap = (anchor.width - dp(FloatingMicBubbleView.RadiusDp * 2)) / 2 - dp(8)
        val layout = overlay(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply {
            x = (if (onRight) anchor.x + discGap - card.measuredWidth else anchor.x + anchor.width - discGap)
                .coerceIn(area.left, max(area.left, area.right - card.measuredWidth))
            y = (anchor.y + anchor.height / 2 - card.measuredHeight / 2)
                .coerceIn(area.top, max(area.top, area.bottom - card.measuredHeight))
        }
        runCatching { windowManager.addView(card, layout) }.onSuccess {
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
        menu?.let { runCatching { windowManager.removeView(it) } }
        menu = null
    }

    /**
     * Toasts from a background app are dropped while its notifications are off (the default on
     * Android 13+), so the bubble shows its own short-lived note at the top of the screen.
     */
    fun note(text: String) {
        removeNote()
        val view = TextView(context).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xE6202124.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        // Touches pass through, so a note over the keyboard never swallows a key press.
        val layout = overlay(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = usableArea().top + dp(12)
        }
        runCatching { windowManager.addView(view, layout) }.onSuccess {
            note = view
            view.postDelayed(hideNote, 5000)
        }
    }

    private fun removeNote() {
        note?.let {
            it.removeCallbacks(hideNote)
            runCatching { windowManager.removeView(it) }
        }
        note = null
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
            update(view, layout)
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
                        update(view, layout)
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

    private companion object {
        const val KeyRight = "docked_right"
        const val KeyY = "docked_y"
        const val EdgeGapDp = 8
        const val Stiffness = 420f
        const val DampingRatio = .55f
    }
}
