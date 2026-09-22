package com.lenix.terminal.bridge

/**
 * Session identifier for Lenix terminal sessions.
 * Adapted from Stryker's NeoTermBridge SessionId.
 *
 * Provides stable IDs for multiple terminal sessions, with special values
 * for new and current session, enabling clean disconnect/reconnect behavior.
 */
data class SessionId(val id: String) {

    fun getSessionId(): String = id

    override fun toString(): String = "LenixSession { id = $id }"

    companion object {
        val NEW_SESSION = SessionId("new")
        val CURRENT_SESSION = SessionId("current")

        fun of(id: String): SessionId = SessionId(id)

        fun generate(): SessionId = SessionId(java.util.UUID.randomUUID().toString())
    }
}
