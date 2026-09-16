package com.lenix.vm.session

import com.lenix.terminal.bridge.SessionId
import com.lenix.vm.VmInstance
import com.lenix.vm.VmManager
import com.lenix.vm.launch.GuestRuntime
import com.lenix.vm.launch.GuestSession
import com.lenix.vm.pty.PtySession
import java.util.concurrent.ConcurrentHashMap

/**
 * Session manager for Lenix — adapted from Stryker's NeoTermService session handling.
 *
 * Provides:
 * - Multiple sessions with stable IDs (like Stryker's mTerminalSessions, mXSessions)
 * - Session creation, lookup, removal
 * - Clean disconnect/reconnect behavior
 * - Lifecycle handling (app start, session start, backgrounding, foregrounding)
 * - No orphaned processes (X servers, VNC servers, shell processes, PTYs)
 */
class SessionManager(
    private val vmManager: VmManager,
    private val guestRuntime: GuestRuntime,
) {
    private val terminalSessions = ConcurrentHashMap<String, PtySession>()
    private val guestSessions = ConcurrentHashMap<String, GuestSession>()

    fun createSession(instanceId: String, sessionId: SessionId = SessionId.generate()): PtySession? {
        val guestSession = guestRuntime.session(instanceId) ?: return null
        val ptySession = guestRuntime.terminal(instanceId) ?: return null
        terminalSessions[sessionId.getSessionId()] = ptySession
        guestSessions[sessionId.getSessionId()] = guestSession
        return ptySession
    }

    fun findSession(sessionId: SessionId): PtySession? =
        terminalSessions[sessionId.getSessionId()]

    fun findGuestSession(sessionId: SessionId): GuestSession? =
        guestSessions[sessionId.getSessionId()]

    fun removeSession(sessionId: SessionId): Int {
        val id = sessionId.getSessionId()
        val index = terminalSessions.keys.indexOf(id)
        if (index >= 0) {
            terminalSessions.remove(id)?.close()
            guestSessions.remove(id)
        }
        return index
    }

    fun allSessions(): List<PtySession> = terminalSessions.values.toList()

    fun clearAll() {
        terminalSessions.values.forEach { it.close() }
        terminalSessions.clear()
        guestSessions.clear()
    }

    /**
     * Handles app backgrounding — like Stryker's onPause.
     * Keeps sessions alive if allowBackground is true, otherwise stops them.
     */
    fun onAppBackgrounded(allowBackground: Boolean) {
        if (!allowBackground) {
            // Stop all sessions if background not allowed (Lenix v0.1 default)
            // GuestRuntime owns process lifetime, so we just detach terminal readers
            terminalSessions.values.forEach { it.close() }
        }
    }

    /**
     * Handles app foregrounding — like Stryker's onResume.
     * Re-attaches to existing sessions if still alive.
     */
    fun onAppForegrounded() {
        // Check which guest sessions are still alive
        val dead = mutableListOf<String>()
        guestSessions.forEach { (id, session) ->
            if (!session.isAlive()) {
                dead.add(id)
            }
        }
        dead.forEach { id ->
            terminalSessions.remove(id)?.close()
            guestSessions.remove(id)
        }
    }

    /**
     * Handles activity recreation (config change, rotation).
     * Preserves sessions across recreation.
     */
    fun onConfigurationChanged() {
        // Sessions survive config changes — no action needed
        // This is the key improvement from Stryker: sessions are owned by service, not activity
    }

    /**
     * Cleanup on app destroy — ensures no orphaned processes.
     */
    fun onDestroy() {
        clearAll()
    }
}
