package com.archdroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance terminal text selection controller optimized for:
 * 1. Samsung DeX & Android Desktop keyboard navigation (Shift + Arrows, Home, End, PgUp, PgDn, Ctrl+C, Enter, Esc).
 * 2. Precision mouse interaction (left-drag select, word double-click, line triple-click, right-click copy/paste, middle-click paste).
 * 3. Seamless native rendering via TerminalView & TerminalRenderer.
 */
object TerminalSelectionController {

    private val getControllerMethod: Method? = try {
        TerminalView::class.java.getDeclaredMethod("getTextSelectionCursorController").apply {
            isAccessible = true
        }
    } catch (_: Throwable) { null }

    private val selX1Field: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionCursorController")
            .getDeclaredField("mSelX1").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val selX2Field: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionCursorController")
            .getDeclaredField("mSelX2").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val selY1Field: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionCursorController")
            .getDeclaredField("mSelY1").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val selY2Field: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionCursorController")
            .getDeclaredField("mSelY2").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val isSelectingField: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionCursorController")
            .getDeclaredField("mIsSelectingText").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val startHandleField: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionCursorController")
            .getDeclaredField("mStartHandle").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val endHandleField: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionCursorController")
            .getDeclaredField("mEndHandle").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val handlePopupField: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionHandleView")
            .getDeclaredField("mHandle").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val handleDrawableField: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionHandleView")
            .getDeclaredField("mHandleDrawable").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val handleLeftDrawableField: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionHandleView")
            .getDeclaredField("mHandleLeftDrawable").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val handleRightDrawableField: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionHandleView")
            .getDeclaredField("mHandleRightDrawable").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val handleWidthField: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionHandleView")
            .getDeclaredField("mHandleWidth").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val handleHeightField: Field? = try {
        Class.forName("com.termux.view.textselection.TextSelectionHandleView")
            .getDeclaredField("mHandleHeight").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    private val renderMethod: Method? = try {
        Class.forName("com.termux.view.textselection.TextSelectionCursorController")
            .getDeclaredMethod("render").apply { isAccessible = true }
    } catch (_: Throwable) { null }

    var isTouchMode: Boolean = false

    /**
     * Controls teardrop handle visibility:
     * - visible = true when user is using touch (finger), showing teardrops.
     * - visible = false in desktop / DeX mode (mouse / physical keyboard), hiding teardrops.
     */
    fun setHandlesVisible(view: TerminalView, visible: Boolean) {
        isTouchMode = visible
        try {
            val controller = getControllerMethod?.invoke(view) ?: return
            val startHandle = startHandleField?.get(controller)
            val endHandle = endHandleField?.get(controller)
            for (handle in listOfNotNull(startHandle, endHandle)) {
                val leftDrawable = handleLeftDrawableField?.get(handle) as? android.graphics.drawable.Drawable
                val rightDrawable = handleRightDrawableField?.get(handle) as? android.graphics.drawable.Drawable
                val activeDrawable = handleDrawableField?.get(handle) as? android.graphics.drawable.Drawable
                val popup = handlePopupField?.get(handle) as? android.widget.PopupWindow
                val handleView = handle as? android.view.View

                if (visible) {
                    leftDrawable?.alpha = 255
                    rightDrawable?.alpha = 255
                    activeDrawable?.alpha = 255
                    handleView?.visibility = android.view.View.VISIBLE
                    handleView?.alpha = 1f
                    popup?.isTouchable = true
                } else {
                    leftDrawable?.alpha = 0
                    rightDrawable?.alpha = 0
                    activeDrawable?.alpha = 0
                    handleView?.visibility = android.view.View.INVISIBLE
                    handleView?.alpha = 0f
                    popup?.isTouchable = false
                    if (popup?.isShowing == true) {
                        try { popup.dismiss() } catch (_: Throwable) {}
                    }
                }
            }

            if (visible && isSelecting(view)) {
                renderMethod?.invoke(controller)
            }
        } catch (_: Throwable) {}
    }

    var anchorCol: Int = 0
        private set
    var anchorRow: Int = 0
        private set
    var activeCol: Int = 0
        private set
    var activeRow: Int = 0
        private set

    private var internalIsSelecting: Boolean = false

    fun isSelecting(view: TerminalView?): Boolean {
        if (view == null) return false
        if (view.isSelectingText) return true
        return internalIsSelecting
    }

    /**
     * Sets selection coordinates directly in TextSelectionCursorController
     * and triggers a high-speed view invalidation for native rendering.
     */
    fun setSelection(
        view: TerminalView,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        anchorC: Int? = null,
        anchorR: Int? = null,
        activeC: Int? = null,
        activeR: Int? = null
    ) {
        val controller = getControllerMethod?.invoke(view) ?: return

        // Normalize coordinates: (selX1, selY1) <= (selX2, selY2)
        val normX1: Int
        val normY1: Int
        val normX2: Int
        val normY2: Int

        if (y1 < y2) {
            normX1 = x1; normY1 = y1
            normX2 = x2; normY2 = y2
        } else if (y1 > y2) {
            normX1 = x2; normY1 = y2
            normX2 = x1; normY2 = y1
        } else {
            normY1 = y1; normY2 = y2
            normX1 = min(x1, x2)
            normX2 = max(x1, x2)
        }

        selX1Field?.setInt(controller, normX1)
        selY1Field?.setInt(controller, normY1)
        selX2Field?.setInt(controller, normX2)
        selY2Field?.setInt(controller, normY2)
        isSelectingField?.setBoolean(controller, true)

        anchorCol = anchorC ?: x1
        anchorRow = anchorR ?: y1
        activeCol = activeC ?: x2
        activeRow = activeR ?: y2
        internalIsSelecting = true

        setHandlesVisible(view, isTouchMode)
        view.invalidate()
    }

    /**
     * Clears any active selection.
     */
    fun clearSelection(view: TerminalView) {
        val controller = getControllerMethod?.invoke(view)
        if (controller != null) {
            selX1Field?.setInt(controller, -1)
            selY1Field?.setInt(controller, -1)
            selX2Field?.setInt(controller, -1)
            selY2Field?.setInt(controller, -1)
            isSelectingField?.setBoolean(controller, false)
        }
        internalIsSelecting = false
        anchorCol = 0
        anchorRow = 0
        activeCol = 0
        activeRow = 0
        setHandlesVisible(view, isTouchMode)
        view.stopTextSelectionMode()
        view.invalidate()
    }

    /**
     * Extracts currently selected text string from the terminal buffer.
     */
    fun getSelectedText(view: TerminalView): String? {
        val emulator = view.mEmulator ?: return null
        val screen = emulator.screen ?: return null
        val controller = getControllerMethod?.invoke(view) ?: return null

        val x1 = selX1Field?.getInt(controller) ?: -1
        val y1 = selY1Field?.getInt(controller) ?: -1
        val x2 = selX2Field?.getInt(controller) ?: -1
        val y2 = selY2Field?.getInt(controller) ?: -1

        if (x1 < 0 || y1 < -screen.activeTranscriptRows || x2 < 0 || y2 >= emulator.mRows) {
            return null
        }

        return screen.getSelectedText(x1, y1, x2, y2)
    }

    /**
     * Copies selected text to the Android Clipboard.
     */
    fun copyToClipboard(view: TerminalView, andClear: Boolean = true): String? {
        val text = getSelectedText(view)
        if (!text.isNullOrEmpty()) {
            val cm = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ArchDroidTerminal", text))
        }
        if (andClear) {
            clearSelection(view)
        }
        return text
    }

    /**
     * Starts selection at an anchor point.
     */
    fun startSelection(view: TerminalView, col: Int, row: Int) {
        anchorCol = col
        anchorRow = row
        activeCol = col
        activeRow = row
        setSelection(view, col, row, col, row, anchorCol, anchorRow, activeCol, activeRow)
    }

    /**
     * Updates active end of selection and handles auto-scrolling viewport.
     */
    fun updateActive(view: TerminalView, col: Int, row: Int) {
        activeCol = col
        activeRow = row
        setSelection(view, anchorCol, anchorRow, activeCol, activeRow, anchorCol, anchorRow, activeCol, activeRow)
        ensureRowVisible(view, activeRow)
    }

    /**
     * Auto-scrolls viewport so the active selection row is visible.
     */
    fun ensureRowVisible(view: TerminalView, targetRow: Int) {
        val emulator = view.mEmulator ?: return
        val currentTop = view.topRow
        val screenRows = emulator.mRows
        val minTop = -(emulator.screen?.activeTranscriptRows ?: 0)

        if (targetRow < currentTop) {
            view.topRow = targetRow.coerceIn(minTop, 0)
            view.invalidate()
        } else if (targetRow >= currentTop + screenRows) {
            view.topRow = (targetRow - screenRows + 1).coerceIn(minTop, 0)
            view.invalidate()
        }
    }

    fun scrollUp(view: TerminalView, lines: Int = 1) {
        val emulator = view.mEmulator ?: return
        val minTop = -(emulator.screen?.activeTranscriptRows ?: 0)
        val currentTop = view.topRow
        val newTop = (currentTop - lines).coerceIn(minTop, 0)
        if (newTop != currentTop) {
            view.topRow = newTop
            view.invalidate()
        }
    }

    fun scrollDown(view: TerminalView, lines: Int = 1) {
        val currentTop = view.topRow
        val newTop = (currentTop + lines).coerceIn(Int.MIN_VALUE, 0)
        if (newTop != currentTop) {
            view.topRow = newTop
            view.invalidate()
        }
    }

    /**
     * Finds word boundaries around (col, row).
     */
    fun getWordRange(view: TerminalView, col: Int, row: Int): Pair<Int, Int> {
        val emulator = view.mEmulator ?: return Pair(col, col)
        val screen = emulator.screen ?: return Pair(col, col)
        val maxCol = emulator.mColumns - 1
        val c = col.coerceIn(0, maxCol)

        val charAtCol = screen.getSelectedText(c, row, c, row)
        if (charAtCol.isEmpty()) return Pair(c, c)

        val initialChar = charAtCol[0]
        val isWordChar = { ch: Char ->
            ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' || ch == '/' || ch == '~' || ch == '$' || ch == ':'
        }

        val condition: (Char) -> Boolean = if (isWordChar(initialChar)) {
            { ch -> isWordChar(ch) }
        } else if (initialChar.isWhitespace()) {
            { ch -> ch.isWhitespace() }
        } else {
            { ch -> !ch.isLetterOrDigit() && !ch.isWhitespace() }
        }

        var x1 = c
        while (x1 > 0) {
            val prev = screen.getSelectedText(x1 - 1, row, x1 - 1, row)
            if (prev.isEmpty() || !condition(prev[0])) break
            x1--
        }

        var x2 = c
        while (x2 < maxCol) {
            val next = screen.getSelectedText(x2 + 1, row, x2 + 1, row)
            if (next.isEmpty() || !condition(next[0])) break
            x2++
        }

        return Pair(x1, x2)
    }

    /**
     * Double-click word selection.
     */
    fun selectWordAt(view: TerminalView, col: Int, row: Int): String? {
        val (x1, x2) = getWordRange(view, col, row)
        setSelection(view, x1, row, x2, row, anchorC = x1, anchorR = row, activeC = x2, activeR = row)
        return copyToClipboard(view, andClear = false)
    }

    /**
     * Triple-click line selection.
     */
    fun selectLineAt(view: TerminalView, row: Int): String? {
        val emulator = view.mEmulator ?: return null
        val x1 = 0
        val x2 = emulator.mColumns - 1
        setSelection(view, x1, row, x2, row, anchorC = x1, anchorR = row, activeC = x2, activeR = row)
        return copyToClipboard(view, andClear = false)
    }

    fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN -> true
            else -> false
        }
    }

    /**
     * Handles keyboard text selection with Shift + Arrow / Nav keys.
     */
    @Suppress("UNUSED_PARAMETER")
    fun handleShiftNavKey(
        view: TerminalView,
        keyCode: Int,
        isCtrl: Boolean,
        isAlt: Boolean
    ): Boolean {
        val emulator = view.mEmulator ?: return false
        val screen = emulator.screen ?: return false
        val maxCols = emulator.mColumns
        val maxRows = emulator.mRows
        val minRow = -(screen.activeTranscriptRows)
        val maxRow = maxRows - 1

        if (!isSelecting(view)) {
            // Anchor at current cursor position
            val curCol = emulator.cursorCol.coerceIn(0, maxCols - 1)
            val curRow = (emulator.cursorRow + view.topRow).coerceIn(minRow, maxRow)
            startSelection(view, curCol, curRow)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isCtrl) {
                    // Jump word backward
                    val newCol = findWordBoundaryLeft(screen, activeCol, activeRow)
                    activeCol = newCol
                } else {
                    if (activeCol > 0) {
                        activeCol--
                    } else if (activeRow > minRow) {
                        activeRow--
                        activeCol = maxCols - 1
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isCtrl) {
                    // Jump word forward
                    val newCol = findWordBoundaryRight(screen, activeCol, activeRow, maxCols)
                    activeCol = newCol
                } else {
                    if (activeCol < maxCols - 1) {
                        activeCol++
                    } else if (activeRow < maxRow) {
                        activeRow++
                        activeCol = 0
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (activeRow > minRow) {
                    activeRow--
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (activeRow < maxRow) {
                    activeRow++
                }
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                activeCol = 0
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                activeCol = maxCols - 1
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                activeRow = (activeRow - maxRows).coerceAtLeast(minRow)
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                activeRow = (activeRow + maxRows).coerceAtMost(maxRow)
            }
            else -> return false
        }

        updateActive(view, activeCol, activeRow)
        return true
    }

    private fun findWordBoundaryLeft(screen: TerminalBuffer, col: Int, row: Int): Int {
        var c = col
        // Skip spaces
        while (c > 0) {
            val ch = screen.getSelectedText(c - 1, row, c - 1, row)
            if (ch.isNotEmpty() && !ch[0].isWhitespace()) break
            c--
        }
        // Skip word
        while (c > 0) {
            val ch = screen.getSelectedText(c - 1, row, c - 1, row)
            if (ch.isEmpty() || ch[0].isWhitespace()) break
            c--
        }
        return c
    }

    private fun findWordBoundaryRight(screen: TerminalBuffer, col: Int, row: Int, maxCols: Int): Int {
        var c = col
        val limit = maxCols - 1
        // Skip word
        while (c < limit) {
            val ch = screen.getSelectedText(c + 1, row, c + 1, row)
            if (ch.isEmpty() || ch[0].isWhitespace()) break
            c++
        }
        // Skip spaces
        while (c < limit) {
            val ch = screen.getSelectedText(c + 1, row, c + 1, row)
            if (ch.isNotEmpty() && !ch[0].isWhitespace()) break
            c++
        }
        return c
    }
}
