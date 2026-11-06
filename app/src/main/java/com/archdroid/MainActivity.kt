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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import android.widget.Toast
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
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private var showSettingsDialog by mutableStateOf(false)
    private var selectedSettingsTab by mutableIntStateOf(0)
    private var keepScreenOn by mutableStateOf(false)
    private var bellHapticEnabled by mutableStateOf(true)
    private var rootfsDiskUsageMb by mutableLongStateOf(0L)
    private var lastCleanedMb by mutableLongStateOf(-1L)
    private var showResetConfirmDialog by mutableStateOf(false)

    private var showBatteryOptDialog by mutableStateOf(false)
    private var backupOpInProgress by mutableStateOf(false)
    private var backupOpStatus by mutableStateOf<String?>(null)
    private var backupOpDetail by mutableStateOf<String?>(null)
    private var backupOpProgress by mutableFloatStateOf(0f)
    private var lastCreatedBackupFile by mutableStateOf<File?>(null)
    private var restoreTargetFile by mutableStateOf<File?>(null)
    private var restoreTargetUri by mutableStateOf<Uri?>(null)
    private var showRestoreConfirmDialog by mutableStateOf(false)
    private lateinit var restorePickerLauncher: ActivityResultLauncher<Array<String>>
    private var contextMenuTabIndex by mutableStateOf<Int?>(null)
    private var lastTabTapTime = 0L
    private var lastTabTapIndex = -1

    private var showAutomationsDialog by mutableStateOf(false)
    private var automationsList by mutableStateOf<List<AutomationItem>>(emptyList())
    private var selectedLogJobId by mutableStateOf<Int?>(null)
    private var selectedLogTitle by mutableStateOf("")
    private var selectedLogScript by mutableStateOf("")
    private var currentLogContent by mutableStateOf("")
    private var showLogDialog by mutableStateOf(false)

    private fun refreshAutomations() {
        automationsList = ArchDroidAutomationManager.getAllAutomations(this)
    }

    private fun showJobLogs(item: AutomationItem) {
        selectedLogJobId = item.id
        selectedLogTitle = item.name
        selectedLogScript = item.script
        currentLogContent = ArchDroidAutomationManager.readRecentLogs(this, item.id, item.script)
        showLogDialog = true
    }

    private fun triggerBellFeedback() {
        if (!bellHapticEnabled) return
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

    private fun executeCreateBackup() {
        if (backupOpInProgress) return
        backupOpInProgress = true
        backupOpStatus = "Preparing Backup"
        backupOpDetail = "Scanning filesystem..."
        backupOpProgress = 0.05f
        lifecycleScope.launch {
            val result = BackupManager.createFullBackup(this@MainActivity) { progress ->
                backupOpStatus = progress.stage
                backupOpDetail = progress.detail
                backupOpProgress = progress.progress
            }
            backupOpInProgress = false
            result.onSuccess { file ->
                lastCreatedBackupFile = file
                backupOpStatus = "Backup Created Successfully"
                backupOpDetail = "Saved to: ${file.name} (${file.length() / (1024 * 1024)} MB)"
            }.onFailure { err ->
                backupOpStatus = "Backup Failed"
                backupOpDetail = err.message ?: "Unknown error"
            }
        }
    }

    private fun executeRestore(file: File?, uri: Uri?) {
        if (backupOpInProgress) return
        backupOpInProgress = true
        backupOpStatus = "Restoring Snapshot"
        backupOpDetail = "Closing active sessions..."
        backupOpProgress = 0.05f
        lifecycleScope.launch {
            try {
                TerminalSessionHolder.terminateAllSessions()
                delay(300)
                val stream = file?.let { FileInputStream(it) }
                    ?: uri?.let { contentResolver.openInputStream(it) }
                    ?: throw IllegalArgumentException("No input source for restore")

                val result = BackupManager.restoreFullBackup(this@MainActivity, stream) { progress ->
                    backupOpStatus = progress.stage
                    backupOpDetail = progress.detail
                    backupOpProgress = progress.progress
                }

                result.onSuccess {
                    backupOpStatus = "Restore Complete"
                    backupOpDetail = "Re-launching terminal..."
                    currentTheme = TerminalThemeManager.loadSavedTheme(this@MainActivity)
                    fontSizePx = TerminalThemeManager.loadFontSize(this@MainActivity, 34)
                    cursorStyle = TerminalThemeManager.loadCursorStyle(this@MainActivity)

                    val first = TerminalSessionHolder.createNewSession(
                        this@MainActivity,
                        sessionClient
                    )
                    terminalViewRef?.attachSession(first)
                    applyCursorStyle(cursorStyle)
                    isReady = true
                    showRestoreConfirmDialog = false
                    showSettingsDialog = false
                    backupOpInProgress = false
                }.onFailure { err ->
                    backupOpInProgress = false
                    backupOpStatus = "Restore Failed"
                    backupOpDetail = err.message ?: "Unknown error"
                }
            } catch (e: Throwable) {
                backupOpInProgress = false
                backupOpStatus = "Restore Failed"
                backupOpDetail = e.message ?: "Unknown error"
            }
        }
    }

    private fun toggleKeepScreenOn(enable: Boolean) {
        keepScreenOn = enable
        getSharedPreferences("archdroid_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("keep_screen_on", enable)
            .apply()
        if (enable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun toggleBellHaptic(enable: Boolean) {
        bellHapticEnabled = enable
        getSharedPreferences("archdroid_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("bell_haptic_enabled", enable)
            .apply()
    }

    private fun refreshRootfsDiskUsage() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rootfs = BootstrapManager.getRootfsDir(this@MainActivity)
                if (rootfs.exists()) {
                    var total = 0L
                    rootfs.walkTopDown().maxDepth(5).forEach { file ->
                        try {
                            val stat = Os.lstat(file.absolutePath)
                            if (!OsConstants.S_ISLNK(stat.st_mode) && file.isFile) {
                                total += file.length()
                            }
                        } catch (_: Throwable) {}
                    }
                    rootfsDiskUsageMb = total / (1024 * 1024)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun cleanTemporaryCaches() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rootfs = BootstrapManager.getRootfsDir(this@MainActivity)
                var cleaned = 0L
                val tmp = File(rootfs, "tmp")
                tmp.listFiles()?.forEach { f ->
                    try {
                        cleaned += f.length()
                        f.deleteRecursively()
                    } catch (_: Throwable) {}
                }
                val pacmanCache = File(rootfs, "var/cache/pacman/pkg")
                pacmanCache.listFiles()?.forEach { f ->
                    try {
                        cleaned += f.length()
                        f.delete()
                    } catch (_: Throwable) {}
                }
                lastCleanedMb = (cleaned / (1024 * 1024)).coerceAtLeast(1)
                refreshRootfsDiskUsage()
            } catch (_: Throwable) {}
        }
    }

    private fun executeFactoryReset() {
        TerminalSessionHolder.terminateAllSessions()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rootfs = BootstrapManager.getRootfsDir(this@MainActivity)
                if (rootfs.exists()) {
                    BootstrapManager.makeTreeWritable(rootfs)
                    rootfs.deleteRecursively()
                }
                launch(Dispatchers.Main) {
                    isReady = false
                    showResetConfirmDialog = false
                    showSettingsDialog = false
                    startBootstrap()
                }
            } catch (_: Throwable) {}
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
        restorePickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                restoreTargetUri = uri
                restoreTargetFile = null
                showRestoreConfirmDialog = true
            }
        }
        fontSizePx = TerminalThemeManager.loadFontSize(this, 34)
        currentTheme = TerminalThemeManager.loadSavedTheme(this)
        cursorStyle = TerminalThemeManager.loadCursorStyle(this)
        isUsingTouch = resources.configuration.keyboard != Configuration.KEYBOARD_QWERTY

        val prefs = getSharedPreferences("archdroid_prefs", Context.MODE_PRIVATE)
        keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        bellHapticEnabled = prefs.getBoolean("bell_haptic_enabled", true)

        try {
            val filter = IntentFilter(TerminalForegroundService.ACTION_APP_EXIT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(exitReceiver, filter)
            }
        } catch (_: Throwable) {}

        startForegroundServiceSafe()
        ArchDroidApiServer.start(this)
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

                    if (showRestoreConfirmDialog) {
                        RestoreConfirmationDialog()
                    }
                    if (showAutomationsDialog) {
                        AutomationsDashboardDialog()
                    }
                    if (showLogDialog) {
                        AutomationLogDialog()
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
                BootstrapManager.writeCustomScripts(BootstrapManager.getRootfsDir(this@MainActivity))
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
                when {
                    showLogDialog -> {
                        showLogDialog = false
                        return true
                    }
                    showAutomationsDialog -> {
                        showAutomationsDialog = false
                        return true
                    }
                    showSettingsDialog -> {
                        if (!backupOpInProgress) showSettingsDialog = false
                        return true
                    }
                    showRenameDialog -> {
                        showRenameDialog = false
                        return true
                    }
                    showResetConfirmDialog -> {
                        showResetConfirmDialog = false
                        return true
                    }
                    showRestoreConfirmDialog -> {
                        if (!backupOpInProgress) showRestoreConfirmDialog = false
                        return true
                    }
                    showBatteryOptDialog -> {
                        showBatteryOptDialog = false
                        return true
                    }
                    else -> {
                        sendToCurrentSession("\u001b")
                        return true
                    }
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (showLogDialog || showAutomationsDialog || showSettingsDialog || showRenameDialog || showResetConfirmDialog || showRestoreConfirmDialog || showBatteryOptDialog) {
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
                text = "Mobile Linux Shell & Automation Platform",
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

                Spacer(modifier = Modifier.height(26.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            restorePickerLauncher.launch(arrayOf("*/*"))
                        } catch (_: Throwable) {}
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF58A6FF))
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBackupRestore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Backup Snapshot Instead", fontSize = 12.sp)
                }
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

                // Automations Dashboard Button
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (showAutomationsDialog) currentTheme.tabActiveColor else currentTheme.tabInactiveColor)
                        .clickable {
                            refreshAutomations()
                            showAutomationsDialog = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "Automations",
                        tint = if (showAutomationsDialog) Color.White else currentTheme.fgColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Settings Gear Button (Option A: Cleanest single icon)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(currentTheme.tabInactiveColor)
                        .clickable {
                            refreshRootfsDiskUsage()
                            showSettingsDialog = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = currentTheme.fgColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Center: ArchDroid TerminalView attached to PRoot session
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(currentTheme.extraKeysBgColor)
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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

        // Unified Settings Hub (Option A)
        if (showSettingsDialog) {
            val settingsTabs = listOf("Appearance", "System", "Backup", "Storage", "About")

            AlertDialog(
                onDismissRequest = {
                    if (!backupOpInProgress) showSettingsDialog = false
                },
                containerColor = currentTheme.topBarColor,
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = Color(0xFF1793D1),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Settings",
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
                            .fillMaxHeight(0.85f)
                    ) {
                        // Category Tabs (Horizontal Chips)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            settingsTabs.forEachIndexed { index, tabName ->
                                val active = selectedSettingsTab == index
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (active) currentTheme.tabActiveColor else currentTheme.tabInactiveColor)
                                        .clickable { selectedSettingsTab = index }
                                        .padding(horizontal = 11.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = tabName,
                                        color = if (active) Color.White else currentTheme.fgColor.copy(alpha = 0.8f),
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = currentTheme.fgColor.copy(alpha = 0.15f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Tab Content
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            when (selectedSettingsTab) {
                                0 -> {
                                    // === Tab 0: Appearance ===
                                    Text(
                                        text = "Color Scheme & Theme",
                                        color = Color(0xFF1793D1),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TerminalThemeManager.PRESETS.forEach { theme ->
                                            val isSelected = currentTheme.name == theme.name
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) currentTheme.tabActiveColor.copy(alpha = 0.25f) else currentTheme.tabInactiveColor)
                                                    .border(
                                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                                        color = if (isSelected) currentTheme.tabActiveColor else Color.Transparent,
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable {
                                                        currentTheme = theme
                                                        TerminalThemeManager.applyTheme(
                                                            this@MainActivity,
                                                            theme,
                                                            sessions,
                                                            terminalViewRef
                                                        )
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = theme.name,
                                                    color = currentTheme.fgColor,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    listOf(theme.bgColor, theme.fgColor, theme.tabActiveColor, theme.cursorColor).forEach { col ->
                                                        Box(
                                                            modifier = Modifier
                                                                .size(12.dp)
                                                                .clip(CircleShape)
                                                                .background(col)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Cursor Style",
                                        color = Color(0xFF1793D1),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            "Block" to TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK,
                                            "Underline" to TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE,
                                            "Bar" to TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
                                        ).forEach { (label, style) ->
                                            val isSelected = cursorStyle == style
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) currentTheme.tabActiveColor else currentTheme.tabInactiveColor)
                                                    .clickable {
                                                        cursorStyle = style
                                                        applyCursorStyle(style)
                                                        TerminalThemeManager.saveCursorStyle(this@MainActivity, style)
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                                            ) {
                                                Text(
                                                    text = label,
                                                    color = if (isSelected) Color.White else currentTheme.fgColor,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Font Size: ${fontSizePx}px",
                                        color = Color(0xFF1793D1),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = { zoomOut() },
                                            colors = ButtonDefaults.buttonColors(containerColor = currentTheme.tabInactiveColor)
                                        ) {
                                            Text("- A", color = currentTheme.fgColor, fontWeight = FontWeight.Bold)
                                        }
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
                                1 -> {
                                    // === Tab 1: System & Background ===
                                    Text(
                                        text = "Background Execution & Keep-Alive",
                                        color = Color(0xFF1793D1),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Battery Optimization Card
                                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                                    val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
                                        pm.isIgnoringBatteryOptimizations(packageName)
                                    } else true

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(currentTheme.tabInactiveColor, RoundedCornerShape(8.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = if (isIgnoringBattery) Color(0xFF7EE787) else Color(0xFFFF7B72),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isIgnoringBattery) "Battery Optimization: Exempted" else "Battery Optimization: Restricted",
                                                    color = if (isIgnoringBattery) Color(0xFF7EE787) else Color(0xFFFF7B72),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (isIgnoringBattery)
                                                    "ArchDroid runs in background without Android Doze killing long-running PRoot processes or AI agents."
                                                else
                                                    "Android may terminate background terminal jobs and LLM agents when the screen is off or app is minimized.",
                                                color = currentTheme.fgColor.copy(alpha = 0.8f),
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp
                                            )
                                            if (!isIgnoringBattery) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Button(
                                                    onClick = {
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
                                                    Text("Disable Restriction", color = Color.White, fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Keep Screen On
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(currentTheme.tabInactiveColor, RoundedCornerShape(8.dp))
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.WbSunny,
                                                contentDescription = null,
                                                tint = currentTheme.fgColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "Keep Screen On",
                                                    color = currentTheme.fgColor,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = "Prevents device sleep while terminal active",
                                                    color = currentTheme.fgColor.copy(alpha = 0.65f),
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = keepScreenOn,
                                            onCheckedChange = { toggleKeepScreenOn(it) }
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Terminal Bell Vibration
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(currentTheme.tabInactiveColor, RoundedCornerShape(8.dp))
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Vibration,
                                                contentDescription = null,
                                                tint = currentTheme.fgColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "Terminal Bell Haptics",
                                                    color = currentTheme.fgColor,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = "Vibrate on terminal ASCII bell (\\a)",
                                                    color = currentTheme.fgColor.copy(alpha = 0.65f),
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = bellHapticEnabled,
                                            onCheckedChange = { toggleBellHaptic(it) }
                                        )
                                    }
                                }
                                2 -> {
                                    // === Tab 2: Backup & Disaster Recovery ===
                                    Text(
                                        text = "Full Environment Snapshot (Mode B)",
                                        color = Color(0xFF1793D1),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Backs up the entire Arch Linux container, installed packages (Python, Node, npm), AI agents (agy, claude, codex, hermes), SSH keys, dotfiles, and app settings into a portable .tar.gz archive.",
                                        color = currentTheme.fgColor.copy(alpha = 0.8f),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    if (backupOpInProgress) {
                                        LinearProgressIndicator(
                                            progress = { backupOpProgress.coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = Color(0xFF1793D1)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = backupOpStatus ?: "Processing...",
                                            color = currentTheme.fgColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = backupOpDetail ?: "",
                                            color = currentTheme.fgColor.copy(alpha = 0.7f),
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                    } else if (backupOpStatus != null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(currentTheme.tabInactiveColor, RoundedCornerShape(8.dp))
                                                .padding(10.dp)
                                        ) {
                                            Column {
                                                Text(
                                                    text = backupOpStatus!!,
                                                    color = if (backupOpStatus!!.contains("Success") || backupOpStatus!!.contains("Complete")) Color(0xFF7EE787) else Color(0xFFFF7B72),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                                if (backupOpDetail != null) {
                                                    Text(
                                                        text = backupOpDetail!!,
                                                        color = currentTheme.fgColor.copy(alpha = 0.8f),
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                if (lastCreatedBackupFile != null && lastCreatedBackupFile!!.exists()) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Button(
                                                        onClick = {
                                                            try {
                                                                val shareIntent = BackupManager.createShareIntent(this@MainActivity, lastCreatedBackupFile!!)
                                                                startActivity(Intent.createChooser(shareIntent, "Share ArchDroid Snapshot"))
                                                            } catch (_: Throwable) {}
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1793D1))
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Share,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(15.dp),
                                                            tint = Color.White
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Share Snapshot", color = Color.White, fontSize = 12.sp)
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                    }

                                    Button(
                                        onClick = { executeCreateBackup() },
                                        enabled = !backupOpInProgress,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1793D1))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Backup,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Create Full Backup Now", color = Color.White, fontSize = 13.sp)
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Restore Snapshot",
                                        color = Color(0xFF1793D1),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))

                                    val localBackups = remember(showSettingsDialog) { BackupManager.listLocalBackups() }
                                    if (localBackups.isNotEmpty()) {
                                        Text(
                                            text = "Recent in /sdcard/Download:",
                                            color = currentTheme.fgColor.copy(alpha = 0.7f),
                                            fontSize = 11.sp
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        localBackups.take(3).forEach { backupFile ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 3.dp)
                                                    .background(currentTheme.tabInactiveColor, RoundedCornerShape(6.dp))
                                                    .clickable(enabled = !backupOpInProgress) {
                                                        restoreTargetFile = backupFile
                                                        restoreTargetUri = null
                                                        showRestoreConfirmDialog = true
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = backupFile.name,
                                                        color = currentTheme.fgColor,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                    Text(
                                                        text = "${backupFile.length() / (1024 * 1024)} MB • ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(backupFile.lastModified()))}",
                                                        color = currentTheme.fgColor.copy(alpha = 0.6f),
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                Text(
                                                    text = "Restore",
                                                    color = Color(0xFF1793D1),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                restorePickerLauncher.launch(arrayOf("*/*"))
                                            } catch (_: Throwable) {}
                                        },
                                        enabled = !backupOpInProgress,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Browse & Import Snapshot File...", fontSize = 12.sp)
                                    }
                                }
                                3 -> {
                                    // === Tab 3: Storage & Maintenance ===
                                    Text(
                                        text = "Rootfs Storage Usage",
                                        color = Color(0xFF1793D1),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(currentTheme.tabInactiveColor, RoundedCornerShape(8.dp))
                                            .padding(14.dp)
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.Storage,
                                                        contentDescription = null,
                                                        tint = Color(0xFF1793D1),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "Container Size",
                                                        color = currentTheme.fgColor,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                                Text(
                                                    text = if (rootfsDiskUsageMb > 0) "~${rootfsDiskUsageMb} MB" else "Calculating...",
                                                    color = Color(0xFF1793D1),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Storage used by Arch Linux rootfs, binaries, python/npm libraries, and user files in /root.",
                                                color = currentTheme.fgColor.copy(alpha = 0.7f),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Clean Temporary Caches",
                                        color = Color(0xFF1793D1),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Purges temporary PRoot sockets, fifos, /tmp/* files, and pacman download caches without touching installed software or code.",
                                        color = currentTheme.fgColor.copy(alpha = 0.75f),
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { cleanTemporaryCaches() },
                                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.tabInactiveColor),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CleaningServices,
                                            contentDescription = null,
                                            tint = currentTheme.fgColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (lastCleanedMb >= 0) "Cleaned Caches! (Reclaimed ~${lastCleanedMb} MB)" else "Clean Temporary Caches",
                                            color = currentTheme.fgColor,
                                            fontSize = 12.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))
                                    HorizontalDivider(color = currentTheme.fgColor.copy(alpha = 0.15f))
                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Factory Reset
                                    Text(
                                        text = "Factory Reset / Re-bootstrap",
                                        color = Color(0xFFFF7B72),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Wipes the PRoot Linux container and re-extracts the factory Arch Linux ARM image. Useful if pacman packages or runtime get corrupted.",
                                        color = currentTheme.fgColor.copy(alpha = 0.7f),
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { showResetConfirmDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Re-bootstrap Container (Reset)", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                                4 -> {
                                    // === Tab 4: About & AI Agents ===
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "ArchDroid",
                                            color = currentTheme.fgColor,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "Version 1.0.0 (ARM64 PRoot)",
                                            color = Color(0xFF1793D1),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.height(14.dp))
                                    }

                                    Text(
                                        text = "Integrated AI Agents:",
                                        color = Color(0xFF1793D1),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    listOf(
                                        "agy" to "Antigravity CLI (Google DeepMind agent)",
                                        "claude" to "Anthropic Claude Code CLI",
                                        "codex" to "OpenAI Codex CLI (musl static binary)",
                                        "hermes" to "NousResearch Hermes Agent (uv python)"
                                    ).forEach { (cmd, desc) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp)
                                                .background(currentTheme.tabInactiveColor, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = cmd,
                                                color = Color(0xFF7EE787),
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = desc,
                                                color = currentTheme.fgColor.copy(alpha = 0.8f),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            try {
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eliakimrosil/ArchDroid")))
                                            } catch (_: Throwable) {}
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.tabInactiveColor),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInBrowser,
                                            contentDescription = null,
                                            tint = currentTheme.fgColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Open GitHub Repository", color = currentTheme.fgColor, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSettingsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.tabActiveColor)
                    ) {
                        Text("Done", color = Color.White)
                    }
                }
            )
        }

        // Factory Reset Confirmation Dialog
        if (showResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                containerColor = currentTheme.topBarColor,
                title = {
                    Text(
                        text = "Reset Linux Container?",
                        color = Color(0xFFFF7B72),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "This will completely delete the Arch Linux container and re-extract the factory image.",
                            color = currentTheme.fgColor.copy(alpha = 0.85f),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠ Any files stored inside the container (/root, installed pacman packages) will be erased. Your backups in /sdcard/Download will NOT be touched.",
                            color = Color(0xFFFF7B72),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { executeFactoryReset() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633))
                    ) {
                        Text("Erase & Re-bootstrap", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmDialog = false }) {
                        Text("Cancel", color = currentTheme.fgColor.copy(alpha = 0.7f))
                    }
                }
            )
        }
    }

    @Composable
    private fun RestoreConfirmationDialog() {
        val displayName = restoreTargetFile?.name
            ?: restoreTargetUri?.lastPathSegment
            ?: "selected backup archive"

        AlertDialog(
            onDismissRequest = {
                if (!backupOpInProgress) showRestoreConfirmDialog = false
            },
            containerColor = currentTheme.topBarColor,
            title = {
                Text(
                    text = "Confirm Full System Restore",
                    color = Color(0xFFFF7B72),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column {
                    if (backupOpInProgress) {
                        LinearProgressIndicator(
                            progress = { backupOpProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF1793D1)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = backupOpStatus ?: "Restoring snapshot...",
                            color = currentTheme.fgColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = backupOpDetail ?: "",
                            color = currentTheme.fgColor.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "Are you sure you want to restore:",
                            color = currentTheme.fgColor.copy(alpha = 0.85f),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = displayName,
                            color = currentTheme.fgColor,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "⚠ WARNING: This will overwrite files in your Arch Linux rootfs with the snapshot and restart all active sessions.",
                            color = Color(0xFFFF7B72),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                if (!backupOpInProgress) {
                    Button(
                        onClick = {
                            executeRestore(restoreTargetFile, restoreTargetUri)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633))
                    ) {
                        Text("Proceed & Restore", color = Color.White)
                    }
                }
            },
            dismissButton = {
                if (!backupOpInProgress) {
                    TextButton(onClick = { showRestoreConfirmDialog = false }) {
                        Text("Cancel", color = currentTheme.fgColor.copy(alpha = 0.7f))
                    }
                }
            }
        )
    }

    @Composable
    private fun AutomationsDashboardDialog() {
        val screenWidth = LocalConfiguration.current.screenWidthDp
        val isWide = screenWidth >= 600
        val focusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            refreshAutomations()
            focusRequester.requestFocus()
        }

        Dialog(
            onDismissRequest = { showAutomationsDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                            showAutomationsDialog = false
                            true
                        } else false
                    }
                    .widthIn(min = if (isWide) 700.dp else 300.dp, max = if (isWide) 1400.dp else 440.dp)
                    .fillMaxWidth(if (isWide) 0.80f else 0.96f)
                    .heightIn(min = if (isWide) 560.dp else 400.dp, max = if (isWide) 880.dp else 750.dp)
                    .fillMaxHeight(if (isWide) 0.80f else 0.88f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = currentTheme.topBarColor),
                border = BorderStroke(1.dp, Color(0xFF30363D))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header: Title, Refresh, Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color(0xFF58A6FF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Automations & Jobs",
                                color = currentTheme.fgColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { refreshAutomations() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = currentTheme.fgColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { showAutomationsDialog = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = currentTheme.fgColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Compact IPC Status Summary Row
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(currentTheme.tabInactiveColor)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF7EE787))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${automationsList.count { it.persisted }} Active Jobs",
                                    color = Color(0xFF7EE787),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "•  IPC :48292",
                                    color = currentTheme.fgColor.copy(alpha = 0.6f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    ArchDroidScheduler.rescheduleAllPersisted(this@MainActivity)
                                    refreshAutomations()
                                    Toast.makeText(this@MainActivity, "Jobs rescheduled in OS", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.height(24.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF58A6FF)),
                                border = BorderStroke(0.8.dp, Color(0xFF58A6FF).copy(alpha = 0.4f))
                            ) {
                                Text("Sync All", fontSize = 10.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Responsive Task Cards: 2-column grid on desktop/wide, 1-column on mobile
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isWide) {
                            items(automationsList.chunked(2)) { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        AutomationCard(pair[0])
                                    }
                                    if (pair.size > 1) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            AutomationCard(pair[1])
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        } else {
                            items(automationsList) { item ->
                                AutomationCard(item)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AutomationCard(item: AutomationItem) {
        val isRunning = ArchDroidAutomationManager.runningJobIds.contains(item.id)
        val isWide = LocalConfiguration.current.screenWidthDp >= 600
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(currentTheme.bgColor)
                .border(
                    width = 1.dp,
                    color = if (isRunning) Color(0xFF58A6FF) else Color(0xFF30363D),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = if (isWide) 14.dp else 10.dp, vertical = if (isWide) 10.dp else 8.dp)
        ) {
            Column {
                // Row 1: ID badge, Automation Full Title, Status Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1F6FEB).copy(alpha = 0.25f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "#${item.id}",
                                color = Color(0xFF58A6FF),
                                fontSize = if (isWide) 11.sp else 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.name,
                            color = currentTheme.fgColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isWide) 14.sp else 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Status badge
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFD29922).copy(alpha = 0.25f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "⚡ RUNNING",
                                color = Color(0xFFE3B341),
                                fontSize = if (isWide) 10.sp else 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (item.persisted) Color(0xFF238636).copy(alpha = 0.2f)
                                    else Color(0xFF8B949E).copy(alpha = 0.2f)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (item.persisted) "SCHEDULED" else "INACTIVE",
                                color = if (item.persisted) Color(0xFF7EE787) else Color(0xFF8B949E),
                                fontSize = if (isWide) 10.sp else 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Row 2: Left column (Schedule & Last run) + Right column (Logs & Run buttons)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🕒 ${item.schedule}",
                            color = currentTheme.fgColor.copy(alpha = 0.85f),
                            fontSize = if (isWide) 11.sp else 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "⏱ Last: ${item.lastRun}",
                            color = Color(0xFF58A6FF),
                            fontSize = if (isWide) 10.5.sp else 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Logs Button
                        OutlinedButton(
                            onClick = { showJobLogs(item) },
                            modifier = Modifier.height(if (isWide) 28.dp else 26.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = currentTheme.fgColor),
                            border = BorderStroke(0.8.dp, Color(0xFF30363D))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Logs", fontSize = if (isWide) 11.sp else 10.sp)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Run Button
                        Button(
                            onClick = {
                                ArchDroidAutomationManager.executeAutomation(
                                    this@MainActivity,
                                    item,
                                    force = true
                                ) {
                                    refreshAutomations()
                                }
                                refreshAutomations()
                            },
                            enabled = !isRunning,
                            modifier = Modifier.height(if (isWide) 28.dp else 26.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isRunning) "..." else "Run", fontSize = if (isWide) 11.sp else 10.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AutomationLogDialog() {
        val screenWidth = LocalConfiguration.current.screenWidthDp
        val isWide = screenWidth >= 600
        val focusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Dialog(
            onDismissRequest = { showLogDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                            showLogDialog = false
                            true
                        } else false
                    }
                    .widthIn(min = if (isWide) 700.dp else 300.dp, max = if (isWide) 1400.dp else 440.dp)
                    .fillMaxWidth(if (isWide) 0.80f else 0.96f)
                    .heightIn(min = if (isWide) 560.dp else 380.dp, max = if (isWide) 880.dp else 750.dp)
                    .fillMaxHeight(if (isWide) 0.80f else 0.84f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = currentTheme.topBarColor),
                border = BorderStroke(1.dp, Color(0xFF30363D))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header: Title, Refresh, Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Logs: $selectedLogTitle (#$selectedLogJobId)",
                            color = currentTheme.fgColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    selectedLogJobId?.let {
                                        currentLogContent = ArchDroidAutomationManager.readRecentLogs(this@MainActivity, it, selectedLogScript)
                                    }
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Log",
                                    tint = currentTheme.fgColor,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { showLogDialog = false },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = currentTheme.fgColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Auto-scrolling Log Box
                    val scrollState = rememberScrollState()
                    LaunchedEffect(currentLogContent) {
                        scrollState.scrollTo(scrollState.maxValue)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0D1117))
                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = currentLogContent,
                                color = Color(0xFFE6EDF3),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action Button: Copy
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Job Logs", currentLogContent))
                                Toast.makeText(this@MainActivity, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
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
