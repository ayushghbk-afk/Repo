package com.lenix.vnc.input

/**
 * Input mapping for VNC desktop — keyboard, mouse, touch.
 * Adapted from Stryker's Xorg input handling (Video.java, Keycodes.java, Mouse).
 *
 * Provides reliable input both in terminal and graphical desktop.
 */

object MouseButtons {
    const val NONE = 0
    const val LEFT = 1
    const val MIDDLE = 2
    const val RIGHT = 4
    const val SCROLL_UP = 8
    const val SCROLL_DOWN = 16
    const val SCROLL_LEFT = 32
    const val SCROLL_RIGHT = 64
}

object KeySym {
    // X11 keysym values for common keys
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val DELETE = 0xFFFF
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val INSERT = 0xFF63
    const val F1 = 0xFFBE
    const val F2 = 0xFFBF
    const val F3 = 0xFFC0
    const val F4 = 0xFFC1
    const val F5 = 0xFFC2
    const val F6 = 0xFFC3
    const val F7 = 0xFFC4
    const val F8 = 0xFFC5
    const val F9 = 0xFFC6
    const val F10 = 0xFFC7
    const val F11 = 0xFFC8
    const val F12 = 0xFFC9
    const val SHIFT_L = 0xFFE1
    const val SHIFT_R = 0xFFE2
    const val CTRL_L = 0xFFE3
    const val CTRL_R = 0xFFE4
    const val ALT_L = 0xFFE9
    const val ALT_R = 0xFFEA
    const val SUPER_L = 0xFFEB
    const val SUPER_R = 0xFFEC

    fun fromChar(c: Char): Int = c.code

    fun fromAndroidKeyCode(keyCode: Int): Int? {
        // Map Android KeyEvent key codes to X11 keysyms
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DEL -> BACKSPACE
            android.view.KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
            android.view.KeyEvent.KEYCODE_TAB -> TAB
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_CENTER -> RETURN
            android.view.KeyEvent.KEYCODE_ESCAPE -> ESCAPE
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            android.view.KeyEvent.KEYCODE_DPAD_UP -> UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            android.view.KeyEvent.KEYCODE_MOVE_HOME -> HOME
            android.view.KeyEvent.KEYCODE_MOVE_END -> END
            android.view.KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
            android.view.KeyEvent.KEYCODE_F1 -> F1
            android.view.KeyEvent.KEYCODE_F2 -> F2
            android.view.KeyEvent.KEYCODE_F3 -> F3
            android.view.KeyEvent.KEYCODE_F4 -> F4
            android.view.KeyEvent.KEYCODE_F5 -> F5
            android.view.KeyEvent.KEYCODE_F6 -> F6
            android.view.KeyEvent.KEYCODE_F7 -> F7
            android.view.KeyEvent.KEYCODE_F8 -> F8
            android.view.KeyEvent.KEYCODE_F9 -> F9
            android.view.KeyEvent.KEYCODE_F10 -> F10
            android.view.KeyEvent.KEYCODE_F11 -> F11
            android.view.KeyEvent.KEYCODE_F12 -> F12
            else -> null
        }
    }
}

/**
 * Touch → mouse mapping inspired by Stryker's DifferentTouchInput.
 * Handles:
 * - Single tap = left click
 * - Long press = right click
 * - Drag = move with button held
 * - Two-finger drag = scroll
 * - Pinch = zoom (client-side)
 */
object TouchMapper {

    data class PointerEvent(
        val buttonMask: Int,
        val x: Int,
        val y: Int,
    )

    fun tap(x: Int, y: Int): List<PointerEvent> = listOf(
        PointerEvent(MouseButtons.LEFT, x, y),
        PointerEvent(MouseButtons.NONE, x, y),
    )

    fun longPress(x: Int, y: Int): List<PointerEvent> = listOf(
        PointerEvent(MouseButtons.RIGHT, x, y),
        PointerEvent(MouseButtons.NONE, x, y),
    )

    fun doubleTap(x: Int, y: Int): List<PointerEvent> = listOf(
        PointerEvent(MouseButtons.LEFT, x, y),
        PointerEvent(MouseButtons.NONE, x, y),
        PointerEvent(MouseButtons.LEFT, x, y),
        PointerEvent(MouseButtons.NONE, x, y),
    )

    fun dragStart(x: Int, y: Int): PointerEvent =
        PointerEvent(MouseButtons.LEFT, x, y)

    fun dragMove(x: Int, y: Int): PointerEvent =
        PointerEvent(MouseButtons.LEFT, x, y)

    fun dragEnd(x: Int, y: Int): PointerEvent =
        PointerEvent(MouseButtons.NONE, x, y)

    fun scrollUp(x: Int, y: Int): List<PointerEvent> = listOf(
        PointerEvent(MouseButtons.SCROLL_UP, x, y),
        PointerEvent(MouseButtons.NONE, x, y),
    )

    fun scrollDown(x: Int, y: Int): List<PointerEvent> = listOf(
        PointerEvent(MouseButtons.SCROLL_DOWN, x, y),
        PointerEvent(MouseButtons.NONE, x, y),
    )
}
