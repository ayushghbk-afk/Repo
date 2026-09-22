package com.lenix.terminal.bridge

import android.content.ComponentName
import android.content.Intent

/**
 * Bridge for communication between Android and terminal sessions.
 * Adapted from Stryker's NeoTermBridge Bridge.java.
 *
 * Provides session creation, IDs, connection lifecycle, and process control
 * for Lenix's terminal environment, adapted to Lenix's package identity.
 */
object LenixBridge {
    const val ACTION_EXECUTE = "com.lenix.action.remote.execute"
    const val ACTION_SILENT_RUN = "com.lenix.action.remote.silent-run"
    const val EXTRA_COMMAND = "com.lenix.extra.remote.execute.command"
    const val EXTRA_EXECUTABLE = "com.lenix.extra.remote.execute.executable"
    const val EXTRA_SESSION_ID = "com.lenix.extra.remote.execute.session"
    const val EXTRA_FOREGROUND = "com.lenix.extra.remote.execute.foreground"

    private const val LENIX_PACKAGE = "com.lenix"
    private const val LENIX_REMOTE_INTERFACE = "com.lenix.ui.TerminalRemoteInterface"
    private val LENIX_COMPONENT = ComponentName(LENIX_PACKAGE, LENIX_REMOTE_INTERFACE)

    fun createExecuteIntent(
        sessionId: SessionId,
        executablePath: String,
        command: String,
        foreground: Boolean,
    ): Intent {
        require(executablePath.isNotEmpty()) { "executablePath must not be empty" }
        return Intent(ACTION_EXECUTE).apply {
            component = LENIX_COMPONENT
            putExtra(EXTRA_EXECUTABLE, executablePath)
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_SESSION_ID, sessionId.getSessionId())
            putExtra(EXTRA_FOREGROUND, foreground)
        }
    }

    fun createCleanExecuteIntent(
        sessionId: SessionId,
        executablePath: String,
        foreground: Boolean,
    ): Intent {
        require(executablePath.isNotEmpty()) { "executablePath must not be empty" }
        return Intent(ACTION_EXECUTE).apply {
            component = LENIX_COMPONENT
            putExtra(EXTRA_EXECUTABLE, executablePath)
            putExtra(EXTRA_SESSION_ID, sessionId.getSessionId())
            putExtra(EXTRA_FOREGROUND, foreground)
        }
    }

    fun createExecuteIntent(command: String): Intent =
        createExecuteIntent(SessionId.NEW_SESSION, "/bin/sh", command, true)

    fun createExecuteIntent(sessionId: SessionId, executablePath: String, command: String): Intent =
        createExecuteIntent(sessionId, executablePath, command, true)

    fun createExecuteIntent(executablePath: String, command: String): Intent =
        createExecuteIntent(SessionId.NEW_SESSION, executablePath, command)

    fun createCleanExecuteIntent(executablePath: String, foreground: Boolean): Intent =
        createCleanExecuteIntent(SessionId.NEW_SESSION, executablePath, foreground)

    fun createExecuteIntent(executablePath: String, command: String, foreground: Boolean): Intent =
        createExecuteIntent(SessionId.NEW_SESSION, executablePath, command, foreground)

    fun parseResult(data: Intent): SessionId? {
        if (data.hasExtra(EXTRA_SESSION_ID)) {
            val handle = data.getStringExtra(EXTRA_SESSION_ID) ?: return null
            return SessionId.of(handle)
        }
        return null
    }
}
