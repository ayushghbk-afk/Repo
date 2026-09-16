package com.lenix.vm.pty

import com.lenix.vm.launch.GuestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The terminal session, exercised against a fake guest process.
 *
 * Where the guest's stdout ends, the reader thread is joined before asserting, so those
 * tests are deterministic without sleeps; the "still running" cases use a stdout that
 * never ends and close the session explicitly.
 */
class PtySessionTest {

    @Test
    fun `guest output reaches the window`() {
        val guest = FakeGuest(stdout = "hello\nworld\n".toStream())
        val session = sessionFor(guest)

        session.start().joinReader()

        assertEquals("hello\nworld\n", session.snapshot.value.text)
        assertEquals(4242L, session.snapshot.value.pid)
        assertFalse(session.snapshot.value.alive)
        assertEquals(PtySession.EXITED_NOTICE, session.snapshot.value.notice)
    }

    @Test
    fun `escape sequences and redraws never reach the window`() {
        // Colour, a carriage return and an erase-in-line: the window shows the final
        // frame only, the way the terminal the guest thinks it is talking to would.
        val guest = FakeGuest(stdout = "\u001B[31mred\u001B[0m\r\u001B[Kdone\n".toStream())
        val session = sessionFor(guest)

        session.start().joinReader()

        assertEquals("done\n", session.snapshot.value.text)
    }

    @Test
    fun `a typed line is echoed locally and written to the shell`() {
        val stdin = RecordingStdin()
        val guest = FakeGuest(stdout = endlessStdout(), stdin = stdin)
        val session = sessionFor(guest).start()

        assertTrue(session.send("ls -l"))

        assertEquals("ls -l\n", stdin.written())
        assertFalse(stdin.isClosed)
        assertTrue(
            "the pipe-backed shell never echoes, so the window must: '${session.snapshot.value.text}'",
            session.snapshot.value.text.contains("$ ls -l"),
        )
        session.close()
    }

    @Test
    fun `a multi-byte character split across reads is decoded once`() {
        // 'é' is 0xC3 0xA9; one byte per read forces the decoder to carry the partial
        // sequence instead of emitting two replacement characters.
        val guest = FakeGuest(stdout = "héllo\n".toByteArray(Charsets.UTF_8).inChunksOf(1))
        val session = sessionFor(guest)

        session.start().joinReader()

        assertEquals("héllo\n", session.snapshot.value.text)
    }

    @Test
    fun `a shell that dies is reported and then refuses input`() {
        val stdin = RecordingStdin()
        val guest = FakeGuest(stdout = "boot\n".toStream(), stdin = stdin)
        val session = sessionFor(guest)

        session.start().joinReader()

        assertFalse(session.snapshot.value.alive)
        assertFalse("input to a dead shell must not throw", session.send("ls"))
        assertEquals("", stdin.written())
        assertEquals(PtySession.EXITED_NOTICE, session.snapshot.value.notice)
    }

    @Test
    fun `a broken stdin pipe becomes a status line, not a crash`() {
        val guest = FakeGuest(stdout = endlessStdout(), stdin = ThrowingStdin())
        val session = sessionFor(guest).start()

        assertFalse(session.send("ls"))
        assertTrue(session.snapshot.value.notice!!.startsWith("Shell closed:"))
        assertFalse(session.snapshot.value.alive)
        session.close()
    }

    @Test
    fun `end of input closes stdin, the only EOF a pipe-backed shell honors`() {
        val stdin = RecordingStdin()
        val guest = FakeGuest(stdout = endlessStdout(), stdin = stdin)
        val session = sessionFor(guest).start()

        assertTrue(session.sendEof())
        assertTrue(stdin.isClosed)
        assertFalse("a second EOF has nothing left to close", session.sendEof())
        session.close()
    }

    @Test
    fun `clear empties the window`() {
        val guest = FakeGuest(stdout = "noise\n".toStream())
        val session = sessionFor(guest)
        session.start().joinReader()
        val revisionBefore = session.snapshot.value.revision

        session.clear()

        assertEquals("", session.snapshot.value.text)
        assertTrue(session.snapshot.value.revision > revisionBefore)
    }

    @Test
    fun `output arriving in many chunks stays in order`() {
        val guest = FakeGuest(stdout = "one\ntwo\nthree\n".toByteArray().inChunksOf(3))
        val session = sessionFor(guest)

        session.start().joinReader()

        assertEquals("one\ntwo\nthree\n", session.snapshot.value.text)
        assertTrue(session.snapshot.value.revision > 1L)
    }

    // --- helpers -------------------------------------------------------------------

    private var readerThread: Thread? = null

    private fun sessionFor(guest: GuestSession): PtySession = PtySession(
        guest = guest,
        threadFactory = { runnable ->
            Thread(runnable, "test-terminal").apply { isDaemon = true }.also { readerThread = it }
        },
    )

    /** Joins the reader thread the session was handed, so EOF has been processed. */
    private fun PtySession.joinReader() {
        readerThread?.join(JOIN_TIMEOUT_MS)
        check(readerThread?.isAlive == false) { "the terminal reader did not finish" }
    }

    private fun String.toStream(): InputStream = toByteArray(Charsets.UTF_8).inputStream()

    /** A stdout that never ends: the reader parks in read() until the session closes. */
    private fun endlessStdout(): InputStream = object : InputStream() {
        override fun read(): Int {
            while (true) {
                Thread.sleep(SLEEP_MS)
            }
        }
    }

    /** Hands out [this] in fixed-size pieces, the way a pipe delivers partial writes. */
    private fun ByteArray.inChunksOf(size: Int): InputStream {
        val chunks: List<ByteArray> = toList().chunked(size).map { it.toByteArray() }
        return object : InputStream() {
            private var chunkIndex = 0
            private var offset = 0

            override fun read(): Int {
                val one = ByteArray(1)
                val count = read(one, 0, 1)
                return if (count < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
                if (chunkIndex >= chunks.size) return -1
                val chunk = chunks[chunkIndex]
                val count = minOf(length, chunk.size - offset)
                System.arraycopy(chunk, offset, target, targetOffset, count)
                offset += count
                if (offset == chunk.size) {
                    chunkIndex += 1
                    offset = 0
                }
                return count
            }
        }
    }

    private class RecordingStdin : OutputStream() {
        private val sink = ByteArrayOutputStream()

        var isClosed: Boolean = false
            private set

        fun written(): String = sink.toString(Charsets.UTF_8.name())

        override fun write(byte: Int) {
            if (isClosed) throw IOException("Stream closed")
            sink.write(byte)
        }

        override fun close() {
            isClosed = true
        }
    }

    private class ThrowingStdin : OutputStream() {
        override fun write(byte: Int) = throw IOException("Broken pipe")
    }

    private class FakeGuest(
        override val stdout: InputStream,
        override val stdin: OutputStream = RecordingStdin(),
    ) : GuestSession {
        val alive = AtomicBoolean(true)
        override val pid: Long = 4242L
        override val vncPort: Int? = null
        override val isPty: Boolean = false
        override fun isAlive(): Boolean = alive.get()
        override fun updateSize(cols: Int, rows: Int) {}
        override fun stop(graceMs: Long) {
            alive.set(false)
        }
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 5_000L
        const val SLEEP_MS = 5L
    }
}
