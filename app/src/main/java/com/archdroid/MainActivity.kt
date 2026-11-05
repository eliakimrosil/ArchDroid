package com.archdroid

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ScaleGestureDetector
import android.view.inputmethod.InputMethodManager
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var bootstrapState by mutableStateOf(
        BootstrapProgress("Initializing", "Checking Arch Linux runtime...", 0f)
    )
    private var isReady by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    private val sessions get() = TerminalSessionHolder.sessions
    private var currentSessionIndex: Int
        get() = TerminalSessionHolder.currentSessionIndex
        set(value) { TerminalSessionHolder.currentSessionIndex = value }
    private val sessionNames get() = TerminalSessionHolder.sessionNames

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TerminalForegroundService.ACTION_APP_EXIT) {
                finishAffinity()
            }
        }
    }

    private var terminalViewRef: TerminalView? = null
    private var fontSizePx by mutableIntStateOf(34)
    private var isUsingTouch by mutableStateOf(false)

    private var ctrlLatched by mutableStateOf(false)
    private var altLatched by mutableStateOf(false)

    private var currentTheme by mutableStateOf(TerminalThemeManager.PRESETS.first())
    private var cursorStyle by mutableIntStateOf(TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK)

    private var showRenameDialog by mutableStateOf(false)
    private var renameTargetIndex by mutableIntStateOf(0)
    private var renameInputText by mutableStateOf("")

    private var showThemeDialog by mutableStateOf(false)
    private var showBatteryOptDialog by mutableStateOf(false)
    private var extraKeysExpanded by mutableStateOf(false)
    private var contextMenuTabIndex by mutableStateOf<Int?>(null)
    private var lastTabTapTime = 0L
    private var lastTabTapIndex = -1

    private fun triggerBellFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(45)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun triggerHapticClick() {
        try {
            terminalViewRef?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) {}
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val prefs = getSharedPreferences("archdroid_prefs", Context.MODE_PRIVATE)
            val alreadyDismissed = prefs.getBoolean("battery_opt_dismissed", false)
            if (!alreadyDismissed) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    showBatteryOptDialog = true
                }
            }
        }
    }

    private fun zoomIn() {
        fontSizePx = (fontSizePx + 2).coerceAtMost(80)
        terminalViewRef?.setTextSize(fontSizePx)
        TerminalThemeManager.saveFontSize(this, fontSizePx)
    }

    private fun zoomOut() {
        fontSizePx = (fontSizePx - 2).coerceAtLeast(18)
        terminalViewRef?.setTextSize(fontSizePx)
        TerminalThemeManager.saveFontSize(this, fontSizePx)
    }

    private fun zoomReset() {
        fontSizePx = 34
        terminalViewRef?.setTextSize(fontSizePx)
        TerminalThemeManager.saveFontSize(this, fontSizePx)
    }

    private fun applyCursorStyle(style: Int) {
        cursorStyle = style
        TerminalThemeManager.applyCursorStyle(sessions, style, terminalViewRef)
    }

    private fun switchSession(idx: Int) {
        if (idx in sessions.indices && idx != currentSessionIndex) {
            TerminalSessionHolder.switchSession(idx)
            terminalViewRef?.attachSession(sessions[idx])
            applyCursorStyle(cursorStyle)
            terminalViewRef?.requestFocus()
        }
    }

    private fun closeSession(idx: Int) {
        TerminalSessionHolder.closeSession(this, idx, sessionClient)
        TerminalSessionHolder.activeSession?.let {
            terminalViewRef?.attachSession(it)
            applyCursorStyle(cursorStyle)
        }
        terminalViewRef?.requestFocus()
    }

    private fun closeCurrentSession() {
        closeSession(currentSessionIndex)
    }

    private fun closeOtherSessions(keepIndex: Int) {
        TerminalSessionHolder.closeOtherSessions(this, keepIndex)
        TerminalSessionHolder.activeSession?.let {
            terminalViewRef?.attachSession(it)
            applyCursorStyle(cursorStyle)
        }
        terminalViewRef?.requestFocus()
    }

    private val sessionClient: TerminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalViewRef?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}

        override fun onSessionFinished(finishedSession: TerminalSession) {
            sessionNames.remove(finishedSession)
            val idx = sessions.indexOf(finishedSession)
            if (idx >= 0) {
                sessions.removeAt(idx)
                if (sessions.isEmpty()) {
                    val newSession = TerminalSessionHolder.createNewSession(
                        this@MainActivity,
                        this
                    )
                    terminalViewRef?.attachSession(newSession)
                    applyCursorStyle(cursorStyle)
                } else {
                    currentSessionIndex = currentSessionIndex.coerceIn(0, sessions.size - 1)
                    terminalViewRef?.attachSession(sessions[currentSessionIndex])
                    applyCursorStyle(cursorStyle)
                }
                TerminalForegroundService.updateNotification(this@MainActivity)
                terminalViewRef?.requestFocus()
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (!text.isNullOrEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ArchDroid", text))
            }
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            pasteFromClipboard()
        }

        override fun onBell(session: TerminalSession) {
            triggerBellFeedback()
        }
        override fun onColorsChanged(session: TerminalSession) {
            terminalViewRef?.onScreenUpdated()
            terminalViewRef?.invalidate()
        }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = cursorStyle

        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val newSize = (fontSizePx * scale).roundToInt().coerceIn(18, 80)
            if (newSize != fontSizePx) {
                fontSizePx = newSize
                terminalViewRef?.setTextSize(fontSizePx)
                TerminalThemeManager.saveFontSize(this@MainActivity, fontSizePx)
            }
            return 1.0f
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            if (e?.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                isUsingTouch = true
            }
            terminalViewRef?.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            terminalViewRef?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (e == null) return false
            val isCtrl = e.isCtrlPressed || ctrlLatched
            val isShift = e.isShiftPressed
            val isAlt = e.isAltPressed || altLatched
            val view = terminalViewRef

            // 0. Active text selection keyboard actions
            if (view != null && TerminalSelectionController.isSelecting(view)) {
                if ((isCtrl && keyCode == KeyEvent.KEYCODE_C) || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    TerminalSelectionController.copyToClipboard(view, andClear = true)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    TerminalSelectionController.clearSelection(view)
                    return true
                }
                if (!isShift && TerminalSelectionController.isNavKey(keyCode)) {
                    TerminalSelectionController.clearSelection(view)
                    // Let key fall through so cursor can move
                }
            }

            // 1. Shift + Navigation keys for text selection
            if (view != null && isShift && TerminalSelectionController.isNavKey(keyCode)) {
                if (TerminalSelectionController.handleShiftNavKey(view, keyCode, isCtrl, isAlt)) {
                    return true
                }
            }

            // 2. Copy & Paste
            if (isCtrl && isShift && keyCode == KeyEvent.KEYCODE_C) {
                if (view != null && TerminalSelectionController.isSelecting(view)) {
                    TerminalSelectionController.copyToClipboard(view, andClear = true)
                }
                return true
            }
            if ((isCtrl && isShift && keyCode == KeyEvent.KEYCODE_V) || (isShift && keyCode == KeyEvent.KEYCODE_INSERT)) {
                pasteFromClipboard()
                return true
            }

            // 2. Tab Navigation: Ctrl + T (New Tab), Ctrl + W (Close Tab)
            if (isCtrl && keyCode == KeyEvent.KEYCODE_T) {
                createNewSession()
                return true
            }
            if (isCtrl && keyCode == KeyEvent.KEYCODE_W) {
                closeCurrentSession()
                return true
            }
            if (isCtrl && keyCode == KeyEvent.KEYCODE_TAB) {
                val next = if (isShift) {
                    if (currentSessionIndex > 0) currentSessionIndex - 1 else sessions.size - 1
                } else {
                    (currentSessionIndex + 1) % sessions.size
                }
                switchSession(next)
                return true
            }
            if (isAlt && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                val target = keyCode - KeyEvent.KEYCODE_1
                if (target < sessions.size) {
                    switchSession(target)
                }
                return true
            }

            // 3. Zoom shortcuts (Ctrl + Plus, Ctrl + Minus, Ctrl + 0)
            if (isCtrl && (keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_NUMPAD_ADD)) {
                zoomIn()
                return true
            }
            if (isCtrl && (keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT)) {
                zoomOut()
                return true
            }
            if (isCtrl && (keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_NUMPAD_0)) {
                zoomReset()
                return true
            }

            // 3. Zoom
            if (isCtrl && (keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_NUMPAD_ADD)) {
                zoomIn()
                return true
            }
            if (isCtrl && (keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT)) {
                zoomOut()
                return true
            }
            if (isCtrl && (keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_NUMPAD_0)) {
                zoomReset()
                return true
            }

            // 4. Physical Escape key pass-through
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                sendToCurrentSession("\u001b")
                return true
            }

            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
            return false
        }

        override fun onLongPress(event: MotionEvent?): Boolean = false

        override fun readControlKey(): Boolean {
            val v = ctrlLatched
            if (v) ctrlLatched = false
            return v
        }

        override fun readAltKey(): Boolean {
            val v = altLatched
            if (v) altLatched = false
            return v
        }

        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(
            codePoint: Int,
            ctrlDown: Boolean,
            session: TerminalSession?
        ): Boolean {
            return false
        }

        override fun onEmulatorSet() {
            applyCursorStyle(cursorStyle)
        }

        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontSizePx = TerminalThemeManager.loadFontSize(this, 34)
        currentTheme = TerminalThemeManager.loadSavedTheme(this)
        cursorStyle = TerminalThemeManager.loadCursorStyle(this)
        isUsingTouch = resources.configuration.keyboard != Configuration.KEYBOARD_QWERTY
        extraKeysExpanded = getSharedPreferences("archdroid_prefs", Context.MODE_PRIVATE)
            .getBoolean("extra_keys_expanded", false)

        try {
            val filter = IntentFilter(TerminalForegroundService.ACTION_APP_EXIT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(exitReceiver, filter)
            }
        } catch (_: Throwable) {}

        startForegroundServiceSafe()
        startBootstrap()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = currentTheme.bgColor,
                    surface = currentTheme.topBarColor,
                    primary = currentTheme.tabActiveColor,
                    secondary = currentTheme.cursorColor
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = currentTheme.bgColor
                ) {
                    if (!isReady) {
                        BootstrapScreen(
                            state = bootstrapState,
                            errorMessage = errorMessage,
                            onRetry = {
                                errorMessage = null
                                startBootstrap()
                            }
                        )
                    } else {
                        TerminalWorkspace()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startForegroundServiceSafe()
        if (isReady) {
            checkBatteryOptimization()
            sessions.forEach { it.updateTerminalSessionClient(sessionClient) }
            val active = TerminalSessionHolder.activeSession
            if (active != null && active.isRunning) {
                if (terminalViewRef?.currentSession !== active) {
                    terminalViewRef?.attachSession(active)
                }
                applyCursorStyle(cursorStyle)
                terminalViewRef?.requestFocus()
            } else if (sessions.isNotEmpty()) {
                val nextValid = sessions.firstOrNull { it.isRunning }
                if (nextValid != null) {
                    currentSessionIndex = sessions.indexOf(nextValid)
                    terminalViewRef?.attachSession(nextValid)
                    applyCursorStyle(cursorStyle)
                    terminalViewRef?.requestFocus()
                }
            }
        }
    }

    private fun startForegroundServiceSafe() {
        try {
            val svc = Intent(this, TerminalForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (_: Throwable) {}
    }

    private fun startBootstrap() {
        lifecycleScope.launch {
            try {
                BootstrapManager.ensureBootstrapped(this@MainActivity) { progress ->
                    bootstrapState = progress
                }
                if (TerminalSessionHolder.sessions.isEmpty()) {
                    val first = TerminalSessionHolder.createNewSession(
                        this@MainActivity,
                        sessionClient
                    )
                    terminalViewRef?.attachSession(first)
                } else {
                    TerminalSessionHolder.sessions.forEach {
                        it.updateTerminalSessionClient(sessionClient)
                    }
                    TerminalSessionHolder.activeSession?.let {
                        terminalViewRef?.attachSession(it)
                    }
                }
                isReady = true
                checkBatteryOptimization()
            } catch (t: Throwable) {
                errorMessage = t.stackTraceToString()
            }
        }
    }

    private fun createNewSession(initialCommand: String? = null) {
        val newSession = TerminalSessionHolder.createNewSession(
            this,
            sessionClient,
            initialCommand
        )
        terminalViewRef?.attachSession(newSession)
        applyCursorStyle(cursorStyle)
        terminalViewRef?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Typing on a physical keyboard automatically hides touch buttons
        if ((event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) == 0 && event.deviceId > 0) {
            if (isUsingTouch) {
                isUsingTouch = false
                terminalViewRef?.let { TerminalSelectionController.setHandlesVisible(it, false) }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isCtrl = event.isCtrlPressed || ctrlLatched
            val isShift = event.isShiftPressed
            val isAlt = event.isAltPressed || altLatched
            val view = terminalViewRef

            // Text selection actions
            if (view != null && TerminalSelectionController.isSelecting(view)) {
                if ((isCtrl && event.keyCode == KeyEvent.KEYCODE_C) || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    TerminalSelectionController.copyToClipboard(view, andClear = true)
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    TerminalSelectionController.clearSelection(view)
                    return true
                }
                if (!isShift && TerminalSelectionController.isNavKey(event.keyCode)) {
                    TerminalSelectionController.clearSelection(view)
                }
            }

            // Keyboard text selection with Shift + Navigation keys
            if (view != null && isShift && TerminalSelectionController.isNavKey(event.keyCode)) {
                if (TerminalSelectionController.handleShiftNavKey(view, event.keyCode, isCtrl, isAlt)) {
                    return true
                }
            }

            if (isCtrl && event.keyCode == KeyEvent.KEYCODE_T) {
                createNewSession()
                return true
            }
            if (isCtrl && event.keyCode == KeyEvent.KEYCODE_W) {
                closeCurrentSession()
                return true
            }
            if (isCtrl && event.keyCode == KeyEvent.KEYCODE_TAB) {
                val next = if (isShift) {
                    if (currentSessionIndex > 0) currentSessionIndex - 1 else sessions.size - 1
                } else {
                    (currentSessionIndex + 1) % sessions.size
                }
                switchSession(next)
                return true
            }
            if (isAlt && event.keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                val target = event.keyCode - KeyEvent.KEYCODE_1
                if (target < sessions.size) {
                    switchSession(target)
                }
                return true
            }
            if (isCtrl && (event.keyCode == KeyEvent.KEYCODE_EQUALS || event.keyCode == KeyEvent.KEYCODE_PLUS || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ADD)) {
                zoomIn()
                return true
            }
            if (isCtrl && (event.keyCode == KeyEvent.KEYCODE_MINUS || event.keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT)) {
                zoomOut()
                return true
            }
            if (isCtrl && (event.keyCode == KeyEvent.KEYCODE_0 || event.keyCode == KeyEvent.KEYCODE_NUMPAD_0)) {
                zoomReset()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                sendToCurrentSession("\u001b")
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val toolType = if (ev.pointerCount > 0) ev.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            if (!isUsingTouch) {
                isUsingTouch = true
                terminalViewRef?.let { TerminalSelectionController.setHandlesVisible(it, true) }
            }
        } else if (toolType == MotionEvent.TOOL_TYPE_MOUSE) {
            if (isUsingTouch) {
                isUsingTouch = false
                terminalViewRef?.let { TerminalSelectionController.setHandlesVisible(it, false) }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val toolType = if (ev.pointerCount > 0) ev.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        if (toolType == MotionEvent.TOOL_TYPE_MOUSE || ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (isUsingTouch) {
                isUsingTouch = false
                terminalViewRef?.let { TerminalSelectionController.setHandlesVisible(it, false) }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(exitReceiver)
        } catch (_: Throwable) {}
        terminalViewRef = null
    }

    private fun sendToCurrentSession(text: String) {
        val session = sessions.getOrNull(currentSessionIndex) ?: return
        session.write(text)
        terminalViewRef?.requestFocus()
    }

    private fun sendKeyEvent(keyCode: Int) {
        val view = terminalViewRef ?: return
        view.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        view.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
        view.requestFocus()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) {
                sendToCurrentSession(text)
            }
        }
    }

    @Composable
    private fun BootstrapScreen(
        state: BootstrapProgress,
        errorMessage: String?,
        onRetry: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ArchDroid",
                color = Color(0xFFE6EDF3),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Mobile Linux Shell • agy • claude • codex • hermes",
                color = Color(0xFF8B949E),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(28.dp))

            if (errorMessage != null) {
                Text(
                    text = "Setup Error:\n$errorMessage",
                    color = Color(0xFFFF7B72),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161B22), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text("Retry Setup")
                }
            } else {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color(0xFF58A6FF),
                    trackColor = Color(0xFF21262D)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = state.step,
                    color = Color(0xFFE6EDF3),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.detail,
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    @Composable
    private fun TerminalWorkspace() {
        val activeSession = sessions.getOrNull(currentSessionIndex)
        val configuration = LocalConfiguration.current
        val hasHardwareKeyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY
        val extraKeysVisible = isUsingTouch || !hasHardwareKeyboard

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(currentTheme.bgColor)
                .imePadding()
        ) {
            // Top Bar: Session Tabs (Scrollable left) + Pinned Theme Button (Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(currentTheme.topBarColor)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scrollable tabs + New Tab '+'
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sessions.forEachIndexed { idx, session ->
                        val selected = idx == currentSessionIndex
                        val tabTitle = sessionNames[session] ?: "tty${idx + 1}"
                        val tabBgColor by animateColorAsState(
                            targetValue = if (selected) currentTheme.tabActiveColor else currentTheme.tabInactiveColor,
                            animationSpec = tween(durationMillis = 150),
                            label = "tabBg"
                        )
                        val tabTextColor by animateColorAsState(
                            targetValue = if (selected) Color.White else currentTheme.fgColor.copy(alpha = 0.85f),
                            animationSpec = tween(durationMillis = 150),
                            label = "tabText"
                        )
                        Box(modifier = Modifier.padding(end = 6.dp)) {
                            @OptIn(ExperimentalComposeUiApi::class)
                            Row(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(tabBgColor)
                                    .pointerInteropFilter { motionEvent ->
                                        val buttonState = motionEvent.buttonState
                                        val isSecondary = (buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                                        val isTertiary = (buttonState and MotionEvent.BUTTON_TERTIARY) != 0
                                        when (motionEvent.actionMasked) {
                                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                                                if (isSecondary) {
                                                    contextMenuTabIndex = idx
                                                    return@pointerInteropFilter true
                                                }
                                                if (isTertiary) {
                                                    if (sessions.size > 1) {
                                                        closeSession(idx)
                                                    }
                                                    return@pointerInteropFilter true
                                                }
                                            }
                                            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                                                if (isSecondary || isTertiary) {
                                                    return@pointerInteropFilter true
                                                }
                                            }
                                        }
                                        false
                                    }
                                    .pointerInput(idx, session) {
                                        detectTapGestures(
                                            onTap = {
                                                val now = SystemClock.uptimeMillis()
                                                if (idx == lastTabTapIndex && (now - lastTabTapTime) < 350L) {
                                                    renameTargetIndex = idx
                                                    renameInputText = sessionNames[session] ?: "tty${idx + 1}"
                                                    showRenameDialog = true
                                                    lastTabTapTime = 0L
                                                    lastTabTapIndex = -1
                                                } else {
                                                    lastTabTapTime = now
                                                    lastTabTapIndex = idx
                                                    switchSession(idx)
                                                }
                                            },
                                            onLongPress = {
                                                contextMenuTabIndex = idx
                                            }
                                        )
                                    }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tabTitle,
                                    color = tabTextColor,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace
                                )
                                // Combined #1 + #5: Close button ONLY shown on active tab with safe spacing
                                if (selected && sessions.size > 1) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.22f))
                                            .clickable { closeSession(idx) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close tab",
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }

                            // Combined #2: Context Menu on Long-Press / Secondary Action
                            DropdownMenu(
                                expanded = (contextMenuTabIndex == idx),
                                onDismissRequest = { contextMenuTabIndex = null },
                                modifier = Modifier
                                    .background(currentTheme.topBarColor)
                                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = null,
                                                tint = currentTheme.fgColor,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text("Rename Tab", color = currentTheme.fgColor, fontSize = 13.sp)
                                        }
                                    },
                                    onClick = {
                                        contextMenuTabIndex = null
                                        renameTargetIndex = idx
                                        renameInputText = sessionNames[session] ?: "tty${idx + 1}"
                                        showRenameDialog = true
                                    }
                                )
                                if (sessions.size > 1) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = null,
                                                    tint = Color(0xFFFF5555),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text("Close Tab", color = Color(0xFFFF5555), fontSize = 13.sp)
                                            }
                                        },
                                        onClick = {
                                            contextMenuTabIndex = null
                                            closeSession(idx)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteSweep,
                                                    contentDescription = null,
                                                    tint = currentTheme.fgColor.copy(alpha = 0.75f),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text("Close Other Tabs", color = currentTheme.fgColor, fontSize = 13.sp)
                                            }
                                        },
                                        onClick = {
                                            contextMenuTabIndex = null
                                            closeOtherSessions(idx)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // New Tab '+' Button
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(currentTheme.tabInactiveColor)
                            .clickable { createNewSession() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Tab",
                            tint = currentTheme.fgColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Theme & Aesthetics Palette Button (Pinned to the right)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(currentTheme.tabInactiveColor)
                        .clickable { showThemeDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = "Themes & Aesthetics",
                        tint = currentTheme.fgColor,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // Center: Real Termux TerminalView attached to ArchDroid PRoot session
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(currentTheme.bgColor),
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTerminalViewClient(viewClient)
                        setTextSize(fontSizePx)
                        setTypeface(TerminalThemeManager.resolveTypeface(ctx))
                        isFocusable = true
                        isFocusableInTouchMode = true
                        keepScreenOn = true
                        TerminalThemeManager.applyTheme(ctx, currentTheme, sessions, this)

                        // Mouse pointer icon: text I-beam on desktop / DeX
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_TEXT)
                        }

                        // Mouse scroll wheel + Ctrl zoom
                        setOnGenericMotionListener { _, event ->
                            if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) || event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                                if (event.action == MotionEvent.ACTION_SCROLL) {
                                    val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                                    val isCtrl = (event.metaState and KeyEvent.META_CTRL_ON) != 0
                                    if (isCtrl) {
                                        if (vScroll > 0) zoomIn() else if (vScroll < 0) zoomOut()
                                        return@setOnGenericMotionListener true
                                    }
                                }
                            }
                            onGenericMotionEvent(event)
                        }

                        var lastMouseClickTime = 0L
                        var lastMouseClickX = 0f
                        var lastMouseClickY = 0f
                        var mouseClickCount = 0
                        var isMouseDragging = false

                        val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            override fun onScale(detector: ScaleGestureDetector): Boolean {
                                val factor = detector.scaleFactor
                                if (factor > 1.01f || factor < 0.99f) {
                                    val newSize = (fontSizePx * factor).roundToInt().coerceIn(18, 80)
                                    if (newSize != fontSizePx) {
                                        fontSizePx = newSize
                                        setTextSize(fontSizePx)
                                    }
                                }
                                return true
                            }

                            override fun onScaleEnd(detector: ScaleGestureDetector) {
                                TerminalThemeManager.saveFontSize(this@MainActivity, fontSizePx)
                            }
                        })

                        var twoFingerStartX = 0f
                        var twoFingerStartY = 0f
                        var twoFingerSwipeTriggered = false

                        // High-performance desktop mouse interaction & Touch gestures:
                        // Touch: Pinch-to-zoom scales font size, two-finger horizontal swipe switches tabs.
                        // Left drag = select text, double-click = word, triple-click = line, auto-copy on release.
                        // Right-click = copy if selecting, else paste. Middle-click = paste.
                        setOnTouchListener { _, event ->
                            val isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                                    (event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

                            if (isMouse) {
                                TerminalSelectionController.setHandlesVisible(this, false)
                                val buttonState = event.buttonState
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        if ((buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                                            // Right-click: Copy if selecting, else Paste
                                            if (TerminalSelectionController.isSelecting(this)) {
                                                TerminalSelectionController.copyToClipboard(this, andClear = true)
                                            } else {
                                                pasteFromClipboard()
                                            }
                                            return@setOnTouchListener true
                                        }
                                        if ((buttonState and MotionEvent.BUTTON_TERTIARY) != 0) {
                                            // Middle-click: Paste
                                            pasteFromClipboard()
                                            return@setOnTouchListener true
                                        }

                                        requestFocus()
                                        val now = SystemClock.uptimeMillis()
                                        val dx = event.x - lastMouseClickX
                                        val dy = event.y - lastMouseClickY
                                        val dist = kotlin.math.hypot(dx, dy)
                                        if (now - lastMouseClickTime < 350L && dist < 24f) {
                                            mouseClickCount++
                                        } else {
                                            mouseClickCount = 1
                                        }
                                        lastMouseClickTime = now
                                        lastMouseClickX = event.x
                                        lastMouseClickY = event.y
                                        isMouseDragging = false

                                        val colRow = getColumnAndRow(event, true)
                                        val maxCols = mEmulator?.mColumns ?: 80
                                        val col = colRow[0].coerceIn(0, maxCols - 1)
                                        val row = colRow[1]

                                        val isShift = (event.metaState and KeyEvent.META_SHIFT_ON) != 0
                                        if (isShift) {
                                            // Shift + Click: extend selection to clicked position
                                            if (!TerminalSelectionController.isSelecting(this)) {
                                                val curCol = (mEmulator?.cursorCol ?: col).coerceIn(0, maxCols - 1)
                                                val curRow = (mEmulator?.cursorRow ?: 0) + topRow
                                                TerminalSelectionController.startSelection(this, curCol, curRow)
                                            }
                                            TerminalSelectionController.updateActive(this, col, row)
                                            TerminalSelectionController.copyToClipboard(this, andClear = false)
                                            return@setOnTouchListener true
                                        }

                                        if (mouseClickCount == 1) {
                                            if (TerminalSelectionController.isSelecting(this)) {
                                                TerminalSelectionController.clearSelection(this)
                                            }
                                            TerminalSelectionController.startSelection(this, col, row)
                                            return@setOnTouchListener false
                                        } else if (mouseClickCount == 2) {
                                            // Double-click: select word and auto-copy
                                            TerminalSelectionController.selectWordAt(this, col, row)
                                            return@setOnTouchListener true
                                        } else if (mouseClickCount >= 3) {
                                            // Triple-click: select full line and auto-copy
                                            TerminalSelectionController.selectLineAt(this, row)
                                            return@setOnTouchListener true
                                        }
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if ((buttonState and MotionEvent.BUTTON_PRIMARY) != 0 && mouseClickCount <= 1) {
                                            val dx = event.x - lastMouseClickX
                                            val dy = event.y - lastMouseClickY
                                            val dist = kotlin.math.hypot(dx, dy)
                                            if (dist > 8f || isMouseDragging) {
                                                isMouseDragging = true
                                                val colRow = getColumnAndRow(event, true)
                                                val emulator = mEmulator ?: return@setOnTouchListener true
                                                val curCol = colRow[0].coerceIn(0, emulator.mColumns - 1)
                                                val minRow = -(emulator.screen?.activeTranscriptRows ?: 0)
                                                val maxRow = emulator.mRows - 1
                                                val curRow = colRow[1].coerceIn(minRow, maxRow)

                                                TerminalSelectionController.updateActive(this, curCol, curRow)

                                                // Auto-scrolling when dragging near/beyond viewport top/bottom
                                                if (event.y < 0) {
                                                    TerminalSelectionController.scrollUp(this)
                                                } else if (event.y > height) {
                                                    TerminalSelectionController.scrollDown(this)
                                                }
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        if (isMouseDragging) {
                                            isMouseDragging = false
                                            // Auto-copy on mouse select release (standard X11/desktop terminal behavior)
                                            TerminalSelectionController.copyToClipboard(this, andClear = false)
                                            return@setOnTouchListener true
                                        }
                                        if (mouseClickCount > 1) {
                                            return@setOnTouchListener true
                                        }
                                        if (mouseClickCount == 1 && !isMouseDragging) {
                                            TerminalSelectionController.clearSelection(this)
                                        }
                                    }
                                }
                            } else {
                                // Touch Gestures: Pinch-to-zoom & Two-finger Tab Switching
                                scaleDetector.onTouchEvent(event)
                                if (scaleDetector.isInProgress) {
                                    twoFingerSwipeTriggered = true
                                    return@setOnTouchListener true
                                }

                                when (event.actionMasked) {
                                    MotionEvent.ACTION_POINTER_DOWN -> {
                                        if (event.pointerCount == 2) {
                                            twoFingerStartX = (event.getX(0) + event.getX(1)) / 2f
                                            twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                                            twoFingerSwipeTriggered = false
                                        }
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (event.pointerCount == 2 && !twoFingerSwipeTriggered) {
                                            val currentMidX = (event.getX(0) + event.getX(1)) / 2f
                                            val currentMidY = (event.getY(0) + event.getY(1)) / 2f
                                            val dx = currentMidX - twoFingerStartX
                                            val dy = currentMidY - twoFingerStartY
                                            if (kotlin.math.abs(dx) > 120f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                                                twoFingerSwipeTriggered = true
                                                if (dx > 0) {
                                                    // Swipe Right -> previous tab
                                                    if (currentSessionIndex > 0) {
                                                        switchSession(currentSessionIndex - 1)
                                                        triggerHapticClick()
                                                    }
                                                } else {
                                                    // Swipe Left -> next tab
                                                    if (currentSessionIndex < sessions.size - 1) {
                                                        switchSession(currentSessionIndex + 1)
                                                        triggerHapticClick()
                                                    }
                                                }
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                                        if (event.pointerCount <= 2) {
                                            twoFingerSwipeTriggered = false
                                        }
                                    }
                                }
                            }
                            false
                        }

                        activeSession?.let { attachSession(it) }
                        TerminalSelectionController.setHandlesVisible(this, isUsingTouch)
                        terminalViewRef = this
                        applyCursorStyle(cursorStyle)
                        requestFocus()
                    }
                },
                update = { view ->
                    terminalViewRef = view
                    if (activeSession != null && view.currentSession !== activeSession) {
                        view.attachSession(activeSession)
                        applyCursorStyle(cursorStyle)
                    }
                }
            )

            // Bottom: Extra-Keys Bar (Shown when touch keys active or when no physical keyboard)
            if (extraKeysVisible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(currentTheme.extraKeysBgColor)
                ) {
                    if (extraKeysExpanded) {
                        // Row 2 (Extended Navigation, Editing & Programming Symbols)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ExtraKeyButton("HOME") { sendToCurrentSession("\u001b[H") }
                            ExtraKeyButton("END") { sendToCurrentSession("\u001b[F") }
                            ExtraKeyButton("PGUP") { sendToCurrentSession("\u001b[5~") }
                            ExtraKeyButton("PGDN") { sendToCurrentSession("\u001b[6~") }
                            ExtraKeyButton("DEL") { sendToCurrentSession("\u001b[3~") }
                            ExtraKeyButton("^Z") { sendToCurrentSession("\u001a") }
                            ExtraKeyButton("{") { sendToCurrentSession("{") }
                            ExtraKeyButton("}") { sendToCurrentSession("}") }
                            ExtraKeyButton("[") { sendToCurrentSession("[") }
                            ExtraKeyButton("]") { sendToCurrentSession("]") }
                            ExtraKeyButton("(") { sendToCurrentSession("(") }
                            ExtraKeyButton(")") { sendToCurrentSession(")") }
                            ExtraKeyButton("\\") { sendToCurrentSession("\\") }
                            ExtraKeyButton(";") { sendToCurrentSession(";") }
                            ExtraKeyButton(":") { sendToCurrentSession(":") }
                            ExtraKeyButton("\"") { sendToCurrentSession("\"") }
                            ExtraKeyButton("'") { sendToCurrentSession("'") }
                            ExtraKeyButton("`") { sendToCurrentSession("`") }
                            ExtraKeyButton("=") { sendToCurrentSession("=") }
                            ExtraKeyButton("_") { sendToCurrentSession("_") }
                            ExtraKeyButton("$") { sendToCurrentSession("$") }
                        }
                    }

                    // Row 1 (Primary Modifier & Navigation Keys + Expand Toggle)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExtraKeyButton(
                            label = if (extraKeysExpanded) "▲" else "▼",
                            active = extraKeysExpanded
                        ) {
                            extraKeysExpanded = !extraKeysExpanded
                            getSharedPreferences("archdroid_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("extra_keys_expanded", extraKeysExpanded)
                                .apply()
                        }
                        ExtraKeyButton("ESC") { sendToCurrentSession("\u001b") }
                        ExtraKeyButton("TAB") { sendToCurrentSession("\t") }
                        ExtraKeyButton(
                            label = "CTRL",
                            active = ctrlLatched
                        ) { ctrlLatched = !ctrlLatched }
                        ExtraKeyButton(
                            label = "ALT",
                            active = altLatched
                        ) { altLatched = !altLatched }
                        ExtraKeyButton("↑") { sendKeyEvent(KeyEvent.KEYCODE_DPAD_UP) }
                        ExtraKeyButton("↓") { sendKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN) }
                        ExtraKeyButton("←") { sendKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT) }
                        ExtraKeyButton("→") { sendKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT) }
                        ExtraKeyButton("^C") { sendToCurrentSession("\u0003") }
                        ExtraKeyButton("-") { sendToCurrentSession("-") }
                        ExtraKeyButton("/") { sendToCurrentSession("/") }
                        ExtraKeyButton("|") { sendToCurrentSession("|") }
                        ExtraKeyButton("~") { sendToCurrentSession("~") }
                        ExtraKeyButton("PASTE") { pasteFromClipboard() }
                    }
                }
            }
        }

        // Rename Session Dialog (Triggered on Double-Click or Long-Press on tab)
        if (showRenameDialog && renameTargetIndex in sessions.indices) {
            val targetSession = sessions[renameTargetIndex]
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                containerColor = currentTheme.topBarColor,
                title = {
                    Text(
                        text = "Rename Tab",
                        color = currentTheme.fgColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Choose a custom name for tab ${renameTargetIndex + 1}:",
                            color = currentTheme.fgColor.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = renameInputText,
                            onValueChange = { renameInputText = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                color = currentTheme.fgColor,
                                fontSize = 14.sp
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Quick Presets:",
                            color = currentTheme.fgColor.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Codex", "Agy", "Claude", "Hermes", "Server", "Python", "Build", "tty${renameTargetIndex + 1}").forEach { chip ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(currentTheme.tabInactiveColor)
                                        .clickable { renameInputText = chip }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = chip,
                                        color = currentTheme.fgColor,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmed = renameInputText.trim()
                            if (trimmed.isEmpty() || trimmed == "tty${renameTargetIndex + 1}") {
                                sessionNames.remove(targetSession)
                            } else {
                                sessionNames[targetSession] = trimmed
                            }
                            showRenameDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.tabActiveColor)
                    ) {
                        Text("Save", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel", color = currentTheme.fgColor.copy(alpha = 0.7f))
                    }
                }
            )
        }

        // Themes & Aesthetics Dialog
        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                containerColor = currentTheme.topBarColor,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = currentTheme.tabActiveColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Themes & Aesthetics",
                            color = currentTheme.fgColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "COLOR SCHEMES",
                            color = currentTheme.fgColor.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TerminalThemeManager.PRESETS.forEach { preset ->
                            val isCurrent = currentTheme.id == preset.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isCurrent) currentTheme.tabActiveColor.copy(alpha = 0.22f) else preset.bgColor)
                                    .border(
                                        width = if (isCurrent) 1.5.dp else 1.dp,
                                        color = if (isCurrent) currentTheme.tabActiveColor else Color.White.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        currentTheme = preset
                                        TerminalThemeManager.applyTheme(
                                            this@MainActivity,
                                            preset,
                                            sessions,
                                            terminalViewRef
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clip(CircleShape)
                                            .background(preset.bgColor)
                                            .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clip(CircleShape)
                                            .background(preset.fgColor)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clip(CircleShape)
                                            .background(preset.tabActiveColor)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clip(CircleShape)
                                            .background(preset.cursorColor)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = preset.name,
                                    color = preset.fgColor,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                RadioButton(
                                    selected = isCurrent,
                                    onClick = {
                                        currentTheme = preset
                                        TerminalThemeManager.applyTheme(
                                            this@MainActivity,
                                            preset,
                                            sessions,
                                            terminalViewRef
                                        )
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = currentTheme.tabActiveColor
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "CURSOR STYLE",
                            color = currentTheme.fgColor.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val cursorOptions = listOf(
                                Pair(TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK, "█ Block"),
                                Pair(TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE, "  Underline"),
                                Pair(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR, "| Bar")
                            )
                            cursorOptions.forEach { (styleVal, label) ->
                                val selected = cursorStyle == styleVal
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) currentTheme.tabActiveColor else currentTheme.tabInactiveColor)
                                        .clickable {
                                            TerminalThemeManager.saveCursorStyle(this@MainActivity, styleVal)
                                            applyCursorStyle(styleVal)
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) Color.White else currentTheme.fgColor,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "FONT SIZE (${fontSizePx} px)",
                            color = currentTheme.fgColor.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Button(
                                onClick = { zoomOut() },
                                colors = ButtonDefaults.buttonColors(containerColor = currentTheme.tabInactiveColor)
                            ) {
                                Text("- A", color = currentTheme.fgColor, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = "${fontSizePx} px",
                                color = currentTheme.fgColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                            Button(
                                onClick = { zoomIn() },
                                colors = ButtonDefaults.buttonColors(containerColor = currentTheme.tabInactiveColor)
                            ) {
                                Text("+ A", color = currentTheme.fgColor, fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { zoomReset() }) {
                                Text("Reset", color = currentTheme.tabActiveColor)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showThemeDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.tabActiveColor)
                    ) {
                        Text("Done", color = Color.White)
                    }
                }
            )
        }

        // OEM Battery Optimization Exemption Dialog
        if (showBatteryOptDialog) {
            AlertDialog(
                onDismissRequest = { showBatteryOptDialog = false },
                containerColor = currentTheme.topBarColor,
                title = {
                    Text(
                        text = "Background Keep-Alive",
                        color = currentTheme.fgColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "ArchDroid runs PRoot, background terminal jobs, and AI agents. Android's aggressive battery optimizer will kill long-running sessions, downloads, and servers when the app is in the background or screen is off.",
                            color = currentTheme.fgColor.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Exempt ArchDroid from battery restrictions to ensure uninterrupted background execution.",
                            color = Color(0xFF1793D1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBatteryOptDialog = false
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:$packageName")
                                    }
                                    startActivity(intent)
                                }
                            } catch (_: Throwable) {
                                try {
                                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                } catch (_: Throwable) {}
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1793D1))
                    ) {
                        Text("Disable Restriction", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showBatteryOptDialog = false
                            getSharedPreferences("archdroid_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("battery_opt_dismissed", true)
                                .apply()
                        }
                    ) {
                        Text("Don't Ask Again", color = currentTheme.fgColor.copy(alpha = 0.6f))
                    }
                }
            )
        }
    }

    @Composable
    private fun ExtraKeyButton(
        label: String,
        active: Boolean = false,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .padding(end = 5.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (active) currentTheme.tabActiveColor else currentTheme.tabInactiveColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = currentTheme.fgColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
