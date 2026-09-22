package com.lenix.vm.pty

import android.util.Base64
import com.lenix.vm.launch.GuestSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * What the terminal window renders: the transcript plus whether a guest shell is
 * attached to it. The screen is a pure function of this, so leaving the screen and
 * coming back cannot lose the transcript or double-attach a reader.
 */
data class TerminalSnapshot(
    val text: String = "",
    val revision: Long = 0L,
    val alive: Boolean = false,
    val pid: Long = 0L,
    /** One-line status under the transcript: why nothing is attached, or how it ended. */
    val notice: String? = null,
    val isPty: Boolean = false,
) {
    companion object {
        /** The window's state while no guest session exists. */
        fun disconnected(notice: String): TerminalSnapshot =
            TerminalSnapshot(text = "", revision = 0L, alive = false, pid = 0L, notice = notice, isPty = false)
    }
}

/**
 * Attaches the terminal window to a live [GuestSession].
 *
 * Improved from Stryker's proven TerminalSession:
 * - Supports both PTY and pipe modes (auto-detected via GuestSession.isPty)
 * - PTY mode: shell echoes, supports signals (Ctrl-C → ETX), window resize via TIOCSWINSZ
 * - Pipe mode: local echo, END SHELL closes stdin (old behavior)
 * - ByteQueue-like handling with incremental UTF8 decoding
 * - Clipboard via OSC 52 sequence detection (Stryker's TerminalEmulator does this)
 * - Proper cleanup via killpg for PTY sessions
 *
 * The guest's stdio is either a PTY (preferred, when libpvmnative available) or a pipe.
 * Exactly one reader may exist — owned by GuestRuntime for as long as guest runs.
 */
class PtySession(
    private val guest: GuestSession,
    private val buffer: TerminalBuffer = TerminalBuffer(),
    private val charset: Charset = Charsets.UTF_8,
    private val prompt: String = DEFAULT_PROMPT,
    private val threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, READER_THREAD).apply { isDaemon = true }
    },
    private val onClipboardText: ((String) -> Unit)? = null,
) {
    // Auto-detect PTY mode from guest session (Stryker pattern: sockMode vs PTY)
    private val isPtyMode = guest.isPty
    private val echoInput = !isPtyMode // PTY echoes for us, pipe doesn't

    private val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** Trailing bytes of an incomplete multi-byte sequence, carried to the next read. */
    private val pendingBytes = ByteArray(4)
    private var pendingCount = 0

    @Volatile
    private var closed = false

    @Volatile
    private var exited = !guest.isAlive()

    @Volatile
    private var stdinClosed = false

    @Volatile
    private var notice: String? = if (guest.isAlive()) null else EXITED_NOTICE

    private val mutableSnapshot = MutableStateFlow(
        TerminalSnapshot(
            text = buffer.text(),
            revision = buffer.revision,
            alive = !exited,
            pid = guest.pid,
            notice = notice,
            isPty = isPtyMode,
        ),
    )
    val snapshot: StateFlow<TerminalSnapshot> = mutableSnapshot.asStateFlow()

    private var reader: Thread? = null

    /** Starts the reader thread. Idempotent. */
    fun start(): PtySession {
        if (reader != null) return this
        reader = threadFactory { pump() }.also(Thread::start)
        return this
    }

    /**
     * Types [line] into the guest shell; a newline is appended. Returns false when there
     * is nothing to write to (never throws — a dead shell is a status line, not a crash).
     */
    fun send(line: String): Boolean =
        write((line + "\n").toByteArray(charset)) { buffer.echoLine(prompt + line) }

    /**
     * Sends raw text without newline (for special keys, etc).
     */
    fun sendRaw(text: String): Boolean =
        write(text.toByteArray(charset)) {}

    /**
     * Sends Ctrl-C (ETX) — works in PTY mode via line discipline → SIGINT.
     * In pipe mode, it's just a byte that bash won't interpret as signal.
     */
    fun sendCtrlC(): Boolean = write(byteArrayOf(0x03)) {}

    /**
     * Sends Ctrl-D (EOT) — EOF in many shells.
     */
    fun sendCtrlD(): Boolean = write(byteArrayOf(0x04)) {}

    /**
     * Closes the guest's stdin, the only end-of-input a pipe-backed shell understands:
     * bash sees EOF and exits. Returns false when stdin was already closed.
     */
    fun sendEof(): Boolean {
        if (closed || exited || stdinClosed) return false
        return try {
            guest.stdin.flush()
            guest.stdin.close()
            stdinClosed = true
            true
        } catch (e: IOException) {
            markExited(e.message?.let { "Shell closed: $it" } ?: EXITED_NOTICE)
            false
        }
    }

    /** Updates terminal size (like Stryker's updateSize). */
    fun updateSize(cols: Int, rows: Int) {
        if (cols < 2 || rows < 2) return
        guest.updateSize(cols, rows)
    }

    /** Clears the scrollback; the guest is untouched. */
    fun clear() {
        buffer.clear()
        publish()
    }

    /** Detaches the window. The guest keeps running; [GuestRuntime] owns its lifetime. */
    fun close() {
        closed = true
        reader?.interrupt()
        reader = null
    }

    private fun write(bytes: ByteArray, echo: () -> Unit): Boolean {
        if (closed) return false
        if (exited || !guest.isAlive()) {
            markExited(EXITED_NOTICE)
            return false
        }
        return try {
            guest.stdin.write(bytes)
            guest.stdin.flush()
            if (echoInput) echo()
            publish()
            true
        } catch (e: IOException) {
            markExited(e.message?.let { "Shell closed: $it" } ?: EXITED_NOTICE)
            false
        }
    }

    private fun pump() {
        val bytes = ByteArray(READ_CHUNK)
        val chars = CharBuffer.allocate(READ_CHUNK * 2)
        try {
            val stdout = guest.stdout
            while (!closed) {
                val read = stdout.read(bytes)
                if (read < 0) break
                if (read == 0) continue
                val text = decode(bytes, read, chars, endOfInput = false)
                if (text.isNotEmpty()) {
                    // Check for OSC 52 clipboard sequence (Stryker's TerminalEmulator handles this)
                    handleOsc52(text)
                    buffer.write(text)
                    publish()
                }
            }
            val tail = decode(bytes, 0, chars, endOfInput = true)
            if (tail.isNotEmpty()) {
                handleOsc52(tail)
                buffer.write(tail)
            }
        } catch (_: IOException) {
            // The guest died or its stdout was closed under us; report it below.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (!closed) markExited(EXITED_NOTICE)
        }
    }

    /**
     * Handles OSC 52 clipboard sequences: ESC ] 52 ; c ; base64 BEL
     * Stryker's TerminalEmulator does this to sync Linux clipboard → Android.
     */
    private fun handleOsc52(text: String) {
        // Simple detection: look for OSC 52 pattern
        // Real implementation would need proper state machine, but we do quick check
        if (!text.contains("\u001B]52;")) return
        try {
            val regex = Regex("""\u001B\]52;[cps]*;([A-Za-z0-9+/=]+)(?:\u0007|\u001B\\)""")
            val match = regex.find(text)
            if (match != null) {
                val b64 = match.groupValues[1]
                val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                onClipboardText?.invoke(decoded)
            }
        } catch (_: Exception) {
        }
    }

    private fun decode(bytes: ByteArray, length: Int, chars: CharBuffer, endOfInput: Boolean): String {
        val input = if (pendingCount == 0) {
            ByteBuffer.wrap(bytes, 0, length)
        } else {
            val combined = ByteArray(pendingCount + length)
            System.arraycopy(pendingBytes, 0, combined, 0, pendingCount)
            System.arraycopy(bytes, 0, combined, pendingCount, length)
            pendingCount = 0
            ByteBuffer.wrap(combined)
        }
        chars.clear()
        decoder.decode(input, chars, endOfInput)
        if (endOfInput) decoder.flush(chars)
        val left = input.remaining()
        if (left in 1..pendingBytes.size) {
            input.get(pendingBytes, 0, left)
            pendingCount = left
        }
        chars.flip()
        return chars.toString()
    }

    private fun markExited(message: String) {
        if (exited && notice == message) return
        exited = true
        notice = message
        publish()
    }

    private fun publish() {
        mutableSnapshot.value = TerminalSnapshot(
            text = buffer.text(),
            revision = buffer.revision,
            alive = !exited && !closed,
            pid = guest.pid,
            notice = notice,
            isPty = isPtyMode,
        )
    }

    companion object {
        const val READER_THREAD = "lenix-terminal"
        const val EXITED_NOTICE = "Guest shell exited. Press START on Home to launch it again."
        const val DEFAULT_PROMPT = "$ "

        private const val READ_CHUNK = 4_096
    }
}
