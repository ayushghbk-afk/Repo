package com.lenix.vm.pty

/**
 * The terminal window's screen + scrollback.
 *
 * A guest shell writes terminal *control* traffic, not plain text: `apt` redraws its
 * progress bar with `\r`, coloured `ls` output is wrapped in `ESC [ … m`, curses apps
 * move the cursor with `ESC [ <row> ; <col> H`, and `\b` erases characters. Rendering
 * those bytes literally (what the old Compose `Text(output)` did) fills the window with
 * `[31m`, `[1K` and stray `^M` glyphs. This buffer applies the parts of the VT100
 * contract a scrollback needs — newline, carriage-return overwrite, backspace, tab
 * stops, the `ESC [ K` / `ESC [ J` erases, and dropping of every other CSI/OSC/charset
 * escape sequence — and hands the UI a plain [text] string.
 *
 * Pure JVM, no Android types, and every method is safe to call from the reader thread
 * while the UI reads [text]: all public members synchronize on the instance.
 *
 * @param maxLines scrollback depth in completed lines; the oldest lines are dropped
 * @param maxColumns hard cap per line, so one enormous write cannot grow without bound
 */
class TerminalBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxColumns: Int = DEFAULT_MAX_COLUMNS,
) {
    private val scrollback = ArrayDeque<String>()
    private var current = StringBuilder()
    private var column = 0
    private var state = State.GROUND
    private val parameters = StringBuilder()
    private var revisionValue = 0L
    private var cachedText: String? = null

    /** Bumped on every mutation; the UI keys its scroll-to-bottom on it. */
    val revision: Long
        get() = revisionValue

    /** Completed scrollback lines plus the line being written. */
    val lineCount: Int
        get() = scrollback.size + 1

    /** Feeds one decoded chunk of guest output through the control-sequence parser. */
    @Synchronized
    fun write(chunk: String) {
        if (chunk.isEmpty()) return
        for (character in chunk) feed(character)
        changed()
    }

    /**
     * Writes text the *app* produced — the local echo of what the user typed. It bypasses
     * the escape parser (the user's own keystrokes are data, never control sequences).
     */
    @Synchronized
    fun echo(text: String) {
        if (text.isEmpty()) return
        for (character in text) {
            if (character == '\n') newline() else put(character)
        }
        changed()
    }

    /** [echo] followed by a newline, i.e. one typed command line. */
    @Synchronized
    fun echoLine(text: String) {
        for (character in text) {
            if (character == '\n') newline() else put(character)
        }
        newline()
        changed()
    }

    /** Drops the whole scrollback (the CLEAR action in the terminal window). */
    @Synchronized
    fun clear() {
        scrollback.clear()
        current = StringBuilder()
        column = 0
        state = State.GROUND
        parameters.setLength(0)
        changed()
    }

    /** The rendered window content: scrollback lines plus the in-progress line. */
    @Synchronized
    fun text(): String = cachedText ?: render().also { cachedText = it }

    private fun render(): String {
        if (scrollback.isEmpty()) return current.toString()
        val rendered = StringBuilder()
        for (line in scrollback) rendered.append(line).append('\n')
        rendered.append(current)
        return rendered.toString()
    }

    private fun feed(character: Char) {
        when (state) {
            State.GROUND -> when {
                character == ESC -> state = State.ESCAPE
                character == '\n' -> newline()
                // A carriage return parks the cursor at column 0: the next characters
                // overwrite the line, which is how progress bars stay on one line.
                character == '\r' -> column = 0
                character == '\b' -> if (column > 0) column -= 1
                character == '\t' -> tab()
                // Other C0 controls and DEL have no printable meaning here.
                character.code < SPACE || character.code == DEL -> Unit
                else -> put(character)
            }

            State.ESCAPE -> {
                parameters.setLength(0)
                state = when (character) {
                    '[' -> State.CSI
                    ']' -> State.OSC
                    // `ESC ( B` & friends: a two-character charset designation.
                    '(', ')', '*', '+' -> State.CHARSET
                    else -> State.GROUND
                }
            }

            State.CHARSET -> state = State.GROUND

            // CSI: parameter bytes, intermediates, then a final byte in 0x40..0x7E.
            State.CSI -> when {
                character.code in CSI_PARAM_FIRST..CSI_PARAM_LAST -> parameters.append(character)
                character.code in CSI_FINAL_FIRST..CSI_FINAL_LAST -> {
                    applyCsi(character)
                    state = State.GROUND
                }
            }

            // OSC: a string terminated by BEL or by ST (ESC \).
            State.OSC -> state = when (character) {
                BEL -> State.GROUND
                ESC -> State.OSC_STRING_TERMINATOR
                else -> State.OSC
            }

            State.OSC_STRING_TERMINATOR -> state = State.GROUND
        }
    }

    /**
     * Runs the two erases that a scrollback has to honour. Everything else (SGR colour,
     * cursor moves, mode switches) is display state a plain text window has no use for.
     */
    private fun applyCsi(final: Char) {
        when (final) {
            // EL — erase in line.
            'K' -> when (firstParameter(default = 0)) {
                1 -> blank(0, column)
                2 -> {
                    current.setLength(0)
                    column = 0
                }

                else -> truncateAtCursor()
            }

            // ED — erase in display: "below the cursor" is only ever the current line,
            // and a full clear keeps the scrollback the user may be reading (as xterm
            // does) instead of destroying history.
            'J' -> when (firstParameter(default = 0)) {
                1 -> blank(0, column)
                0 -> truncateAtCursor()
                else -> {
                    current.setLength(0)
                    column = 0
                }
            }
        }
    }

    private fun firstParameter(default: Int): Int =
        parameters.toString().substringBefore(';').toIntOrNull() ?: default

    private fun truncateAtCursor() {
        if (column < current.length) current.setLength(column)
    }

    private fun blank(from: Int, to: Int) {
        for (index in from until minOf(to, current.length)) current.setCharAt(index, ' ')
    }

    private fun put(character: Char) {
        if (column > current.length) {
            repeat(column - current.length) { current.append(' ') }
        }
        if (column == current.length) current.append(character) else current.setCharAt(column, character)
        column += 1
        if (current.length > maxColumns) {
            // Keep the tail: an over-long line is almost always a redrawn progress bar.
            val dropped = current.length - maxColumns
            current.delete(0, dropped)
            column = (column - dropped).coerceAtLeast(0)
        }
    }

    private fun tab() {
        val stop = (column / TAB_WIDTH + 1) * TAB_WIDTH
        while (column < stop) put(' ')
    }

    private fun newline() {
        scrollback.addLast(current.toString())
        while (scrollback.size > maxLines) scrollback.removeFirst()
        current = StringBuilder()
        column = 0
    }

    private fun changed() {
        revisionValue += 1
        cachedText = null
    }

    private enum class State {
        GROUND,
        ESCAPE,
        CHARSET,
        CSI,
        OSC,
        OSC_STRING_TERMINATOR,
    }

    companion object {
        const val DEFAULT_MAX_LINES = 1_000
        const val DEFAULT_MAX_COLUMNS = 1_000

        private const val ESC = '\u001B'
        private const val BEL = '\u0007'
        private const val SPACE = 0x20
        private const val DEL = 0x7F
        private const val TAB_WIDTH = 8
        private const val CSI_PARAM_FIRST = 0x30
        private const val CSI_PARAM_LAST = 0x3F
        private const val CSI_FINAL_FIRST = 0x40
        private const val CSI_FINAL_LAST = 0x7E
    }
}
