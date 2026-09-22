package com.lenix.vm.pty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window must show what a terminal would show, not the raw byte stream: the guest
 * shell redraws progress bars with `\r` and colours output with `ESC [ … m`, and none of
 * that belongs on screen.
 */
class TerminalBufferTest {

    @Test
    fun `plain output is kept line by line`() {
        val buffer = TerminalBuffer()

        buffer.write("hello\nworld\n")

        assertEquals("hello\nworld\n", buffer.text())
        assertEquals(3, buffer.lineCount)
    }

    @Test
    fun `carriage return overwrites the line instead of printing both frames`() {
        val buffer = TerminalBuffer()

        buffer.write("Downloading  10%\rDownloading 100%\nDone\n")

        assertEquals("Downloading 100%\nDone\n", buffer.text())
    }

    @Test
    fun `carriage return mid-line replaces only what follows`() {
        val buffer = TerminalBuffer()

        buffer.write("abcdef\rXY")

        // Cursor parks at column 0: XY overwrites ab, cdef survives.
        assertEquals("XYcdef", buffer.text())
    }

    @Test
    fun `backspace moves the cursor back so the next character replaces`() {
        val buffer = TerminalBuffer()

        buffer.write("one\b\b1")

        assertEquals("o1e", buffer.text())
    }

    @Test
    fun `tabs advance to the next tab stop`() {
        val buffer = TerminalBuffer()

        buffer.write("a\tb")

        assertEquals("a       b", buffer.text())
    }

    @Test
    fun `colour and cursor sequences are dropped, their text is kept`() {
        val buffer = TerminalBuffer()

        buffer.write("\u001B[31mred\u001B[0m \u001B[1;32mbold\u001B[m\n\u001B[2J\u001B[Hafter clear")

        assertEquals("red bold\nafter clear", buffer.text())
    }

    @Test
    fun `erase-in-line removes the tail a shorter redraw leaves behind`() {
        val buffer = TerminalBuffer()

        buffer.write("Downloading 100%\r\u001B[KDone\n")

        assertEquals("Done\n", buffer.text())
    }

    @Test
    fun `erase-in-line honours its mode`() {
        val zero = TerminalBuffer().apply { write("abcdef\rXY\u001B[0K") }
        val one = TerminalBuffer().apply { write("abcdef\rXY\u001B[1K") }
        val two = TerminalBuffer().apply { write("abcdef\rXY\u001B[2K") }

        assertEquals("XY", zero.text())
        assertEquals("  cdef", one.text())
        assertEquals("", two.text())
    }

    @Test
    fun `erase-in-display clears the line but keeps the scrollback`() {
        val buffer = TerminalBuffer()

        buffer.write("history\npartial\u001B[2J")

        assertEquals("history\n", buffer.text())
    }

    @Test
    fun `osc window-title sequences are dropped for both terminators`() {
        val buffer = TerminalBuffer()

        buffer.write("\u001B]0;root@lenix: ~\u0007bel form")
        buffer.write("\n\u001B]0;root@lenix: ~\u001B\\st form")

        assertEquals("bel form\nst form", buffer.text())
    }

    @Test
    fun `charset designations and other controls are dropped`() {
        val buffer = TerminalBuffer()

        buffer.write("\u001B(B\u0000text\u007F\n")

        assertEquals("text\n", buffer.text())
    }

    @Test
    fun `an escape sequence split across two reads is still one sequence`() {
        val buffer = TerminalBuffer()

        buffer.write("before\u001B[3")
        buffer.write("1mafter")

        assertEquals("beforeafter", buffer.text())
    }

    @Test
    fun `scrollback drops the oldest lines past its depth`() {
        val buffer = TerminalBuffer(maxLines = 3)

        buffer.write("1\n2\n3\n4\n5\n")

        assertEquals("3\n4\n5\n", buffer.text())
        assertEquals(4, buffer.lineCount)
    }

    @Test
    fun `one huge line is capped and keeps its tail`() {
        val buffer = TerminalBuffer(maxColumns = 10)

        buffer.write("0123456789abcdef")

        assertEquals("6789abcdef", buffer.text())
    }

    @Test
    fun `local echo renders a typed command as a prompt and a line`() {
        val buffer = TerminalBuffer()

        buffer.echoLine("$ ls -l")
        buffer.write("total 0\n")

        assertEquals("$ ls -l\ntotal 0\n", buffer.text())
    }

    @Test
    fun `local echo is data, never a control sequence`() {
        val buffer = TerminalBuffer()

        buffer.echoLine("echo \u001B[31m")

        assertEquals("echo \u001B[31m\n", buffer.text())
    }

    @Test
    fun `clear empties the window and bumps the revision`() {
        val buffer = TerminalBuffer()
        buffer.write("noise\n")
        val before = buffer.revision

        buffer.clear()

        assertEquals("", buffer.text())
        assertTrue("revision must move so the UI re-renders", buffer.revision > before)
    }

    @Test
    fun `text is cached until the next mutation`() {
        val buffer = TerminalBuffer()
        buffer.write("a\n")

        val first = buffer.text()
        val second = buffer.text()
        // A redraw that changes nothing visible must still invalidate the cache.
        buffer.write("\r")

        assertTrue("unchanged reads must reuse the rendered string", first === second)
        assertFalse("a write must re-render", first === buffer.text())
        assertEquals("a\n", buffer.text())
    }
}
