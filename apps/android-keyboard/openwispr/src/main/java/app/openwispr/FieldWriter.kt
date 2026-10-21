package app.openwispr

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

/** The field's text with a dictation taken back out, or null if it is no longer where it was put. */
fun removeDictation(current: String, start: Int, inserted: String): String? {
    val end = start + inserted.length
    if (inserted.isEmpty() || start < 0 || end > current.length) return null
    if (current.substring(start, end) != inserted) return null
    return current.removeRange(start, end)
}

/**
 * Writes dictation into another app's focused text field through accessibility, and can take the
 * last one back out.
 */
class FieldWriter(private val inputFocus: () -> AccessibilityNodeInfo?) {
    enum class Result { Inserted, Pasted, NoField, Refused, Empty }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Latest caret from selection events in the focused field. After SET_TEXT, Chrome's node keeps
     * answering 0 for the selection (and changes identity) while its events stay accurate, so the
     * next dictation would land at the start.
     */
    private var reportedCaret: Pair<Int, Int>? = null

    /** Where the last dictation went, so it can be taken back out. */
    private class Inserted(val node: AccessibilityNodeInfo, val start: Int, val text: String)
    private var lastInserted: Inserted? = null

    val canUndo get() = lastInserted != null

    fun forgetLast() {
        lastInserted = null
    }

    /** Returns true for selection events, which change nothing else. */
    fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            reportedCaret = event.fromIndex to event.toIndex
            return true
        }
        // Selection events only make sense for the field that had focus when they came.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) reportedCaret = null
        return false
    }

    private fun caretOf(node: AccessibilityNodeInfo): Pair<Int, Int> =
        reportedCaret ?: (node.textSelectionStart to node.textSelectionEnd)

    fun focusedEditable(): AccessibilityNodeInfo? = focusedField()?.takeUnless { it.isPassword }

    /**
     * The editable field with input focus. Chrome reports its whole web view as the input focus
     * until its accessibility tree has been read, so look inside a non-editable focus for the field.
     */
    fun focusedField(): AccessibilityNodeInfo? {
        val focus = inputFocus() ?: return null
        return if (focus.isEditable) focus else findFocusedEditable(focus, 0)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        if (depth >= MaxFocusSearchDepth) return null
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> findFocusedEditable(child, depth + 1)?.let { return it } }
        }
        return null
    }

    /**
     * Inserts at the focused field's cursor, or into [fallback] (the field the recording was for)
     * when focus has moved. [Result.Pasted] means [paste] put the text on the clipboard first.
     */
    fun insert(
        result: String,
        fallback: AccessibilityNodeInfo?,
        sanitize: (before: String, after: String) -> String,
        paste: (String) -> Unit,
    ): Result {
        if (result.isBlank()) return Result.Empty
        // Cached nodes keep old text: the service does not listen for text changes, so re-read.
        val node = focusedEditable()?.also { it.refresh() } ?: fallback?.takeIf { it.refresh() && it.isEditable }
            ?: return Result.NoField
        // An empty field reports its hint as text. Android 7 has no API to tell the two apart, so
        // there the hint may stay in front of the dictation.
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val (selectionStart, selectionEnd) = caretOf(node)
        val (newText, cursor, text) = spliceDictation(
            current = if (showingHint) "" else node.text?.toString().orEmpty(),
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            result = result,
            sanitize = sanitize,
        ) ?: return Result.Empty

        if (setText(node, newText)) {
            placeCursor(node, cursor)
            lastInserted = Inserted(node, cursor - text.length, text)
            return Result.Inserted
        }
        // Web views and some custom editors refuse SET_TEXT but accept a paste at the cursor.
        paste(text)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return Result.Refused
        reportedCaret = cursor to cursor
        lastInserted = Inserted(node, cursor - text.length, text)
        return Result.Pasted
    }

    /** Removes the last dictation if it is still exactly where it was put; false if it can't. */
    fun undo(): Boolean {
        val last = lastInserted ?: return false
        lastInserted = null
        val node = last.node.takeIf { it.refresh() } ?: return false
        val restored = node.text?.toString()?.let { removeDictation(it, last.start, last.text) } ?: return false
        if (setText(node, restored)) {
            placeCursor(node, last.start)
            return true
        }
        // Editors that took the dictation as a paste: select it and cut it back out.
        return select(node, last.start, last.start + last.text.length) &&
            node.performAction(AccessibilityNodeInfo.ACTION_CUT)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })

    /**
     * Web editors (contenteditable in Chrome) apply SET_TEXT asynchronously and then reset the caret
     * to the start, overriding a selection set straight away. Re-check shortly after and move it to
     * the end of the dictation while it is still at the start; a caret the user has moved since is
     * left alone.
     */
    private fun placeCursor(node: AccessibilityNodeInfo, cursor: Int) {
        // Some editors (Flutter) send no selection event for our own edits; without this the caret
        // from the user's last typing would win and the next dictation would land there.
        reportedCaret = cursor to cursor
        select(node, cursor, cursor)
        for (delay in CursorRetryDelaysMs) handler.postDelayed({
            if (cursor != 0 && caretOf(node) == (0 to 0)) select(node, cursor, cursor)
        }, delay)
    }

    private fun select(node: AccessibilityNodeInfo, start: Int, end: Int) =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        })

    private companion object {
        const val MaxFocusSearchDepth = 60
        val CursorRetryDelaysMs = longArrayOf(120, 400, 900)
    }
}
