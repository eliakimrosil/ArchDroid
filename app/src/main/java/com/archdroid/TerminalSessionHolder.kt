package com.archdroid

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object TerminalSessionHolder {

    val sessions = mutableStateListOf<TerminalSession>()
    val sessionNames = mutableStateMapOf<TerminalSession, String>()
    var currentSessionIndex by mutableIntStateOf(0)

    val activeSession: TerminalSession?
        get() = sessions.getOrNull(currentSessionIndex)

    private val scope = CoroutineScope(Dispatchers.IO)

    fun createNewSession(
        context: Context,
        client: TerminalSessionClient,
        initialCommand: String? = null
    ): TerminalSession {
        val session = ProotSessionManager.createAlpineSession(context, client, initialCommand)
        sessions.add(session)
        currentSessionIndex = sessions.size - 1
        TerminalForegroundService.updateNotification(context)
        return session
    }

    fun switchSession(idx: Int) {
        if (idx in sessions.indices) {
            currentSessionIndex = idx
        }
    }

    fun terminateSessionGracefully(session: TerminalSession) {
        try {
            Os.kill(session.pid, OsConstants.SIGHUP)
        } catch (_: Throwable) {
            try {
                session.write("exit\r")
            } catch (_: Throwable) {}
        }
        scope.launch {
            delay(200)
            if (session.isRunning) {
                session.finishIfRunning()
            }
        }
    }

    fun closeSession(context: Context, idx: Int, client: TerminalSessionClient) {
        if (idx in sessions.indices) {
            val sessionToClose = sessions[idx]
            sessionNames.remove(sessionToClose)
            sessions.removeAt(idx)
            terminateSessionGracefully(sessionToClose)

            if (sessions.isEmpty()) {
                val newSession = ProotSessionManager.createAlpineSession(context, client)
                sessions.add(newSession)
                currentSessionIndex = 0
            } else {
                currentSessionIndex = currentSessionIndex.coerceIn(0, sessions.size - 1)
            }
            TerminalForegroundService.updateNotification(context)
        }
    }

    fun closeOtherSessions(context: Context, keepIndex: Int) {
        if (keepIndex in sessions.indices) {
            val sessionToKeep = sessions[keepIndex]
            val toRemove = sessions.filter { it != sessionToKeep }
            toRemove.forEach { s ->
                sessionNames.remove(s)
                terminateSessionGracefully(s)
            }
            sessions.clear()
            sessions.add(sessionToKeep)
            currentSessionIndex = 0
            TerminalForegroundService.updateNotification(context)
        }
    }

    fun terminateAllSessions() {
        val all = sessions.toList()
        sessions.clear()
        sessionNames.clear()
        currentSessionIndex = 0
        all.forEach { s ->
            terminateSessionGracefully(s)
        }
    }
}
