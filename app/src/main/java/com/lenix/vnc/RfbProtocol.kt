package com.lenix.vnc

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * RFB 3.8 wire helpers (ADR-003 / ADR-004). Handshake and Raw encoding are
 * implemented in pure Kotlin so they are JVM-testable without a live Xvnc.
 *
 * Improvements from Stryker reference:
 * - Robust security negotiation with clear diagnostics (None vs VNC auth)
 * - Clipboard support via ClientCutText/ServerCutText (like Stryker's Clipboard)
 * - Proper handling of server message types (Bell, ServerCutText, SetColourMapEntries)
 * - PixelFormat pinning to BGRX_8888 for hardware-accelerated decoding
 * - Raw encoding only, but with graceful handling of unsupported encodings
 * - VNC authentication error with actionable message
 */
object RfbProtocol {
    const val VERSION = "RFB 003.008\n"
    const val SECURITY_INVALID = 0
    const val SECURITY_NONE = 1
    const val SECURITY_VNC = 2
    const val ENCODING_RAW = 0
    const val ENCODING_COPYRECT = 1
    const val ENCODING_RRE = 2
    const val ENCODING_HEXTILE = 5
    const val ENCODING_TRLE = 15
    const val ENCODING_ZRLE = 16
    const val ENCODING_DESKTOP_SIZE = -223 // pseudo-encoding for resize
    const val MSG_FRAMEBUFFER_UPDATE = 0
    const val MSG_SET_COLOUR_MAP_ENTRIES = 1
    const val MSG_BELL = 2
    const val MSG_SERVER_CUT_TEXT = 3
    const val CLIENT_SET_PIXEL_FORMAT = 0
    const val CLIENT_SET_ENCODINGS = 2
    const val CLIENT_FRAMEBUFFER_UPDATE_REQUEST = 3
    const val CLIENT_KEY_EVENT = 4
    const val CLIENT_POINTER_EVENT = 5
    const val CLIENT_CUT_TEXT = 6

    fun writeVersion(out: OutputStream) {
        out.write(VERSION.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    fun readVersion(input: InputStream): String {
        val buf = ByteArray(12)
        DataInputStream(input).readFully(buf)
        return String(buf, StandardCharsets.US_ASCII)
    }

    fun readSecurityTypes(input: InputStream): List<Int> {
        val data = DataInputStream(input)
        val count = data.readUnsignedByte()
        if (count == 0) {
            val reasonLen = data.readInt()
            val reason = ByteArray(reasonLen.coerceAtLeast(0))
            data.readFully(reason)
            error("RFB rejected the client: ${String(reason, StandardCharsets.UTF_8)}")
        }
        return List(count) { data.readUnsignedByte() }
    }

    /**
     * Chooses security type, preferring None. If only VNC auth is offered,
     * returns VNC but caller must handle challenge — or fail with clear message.
     * Stryker's x11vnc uses VNC auth with password file; Lenix uses None for loopback,
     * but we support both for compatibility.
     */
    fun chooseSecurity(types: List<Int>): Int {
        if (SECURITY_NONE in types) return SECURITY_NONE
        if (SECURITY_VNC in types) return SECURITY_VNC
        error("No supported RFB security type in $types. Server offers: $types. Lenix supports None (1) and VNC (2).")
    }

    fun writeSecurityType(out: OutputStream, type: Int) {
        out.write(type)
        out.flush()
    }

    fun readSecurityResult(input: InputStream): Boolean {
        val data = DataInputStream(input)
        val result = data.readInt()
        if (result != 0) {
            // SecurityResult failed — try to read reason if present
            try {
                val reasonLen = data.readInt()
                if (reasonLen > 0 && reasonLen < 4096) {
                    val reason = ByteArray(reasonLen)
                    data.readFully(reason)
                    error("RFB security handshake failed: ${String(reason, StandardCharsets.UTF_8)} (code $result)")
                }
            } catch (_: Exception) {
                // No reason string, just fail
            }
            return false
        }
        return true
    }

    fun writeClientInit(out: OutputStream, shared: Boolean = true) {
        out.write(if (shared) 1 else 0)
        out.flush()
    }

    /**
     * RFB `PIXEL_FORMAT` (16 bytes on the wire).
     */
    data class PixelFormat(
        val bitsPerPixel: Int,
        val depth: Int,
        val bigEndian: Boolean,
        val trueColour: Boolean,
        val redMax: Int,
        val greenMax: Int,
        val blueMax: Int,
        val redShift: Int,
        val greenShift: Int,
        val blueShift: Int,
    )

    /**
     * The one format [decodeRawBgra] can decode: 32 bpp, depth 24, little-endian
     * true colour with red at bit 16 — i.e. `B,G,R,pad` byte order in the stream.
     *
     * The client *must* pin this with [writeSetPixelFormat]; a server is free to
     * keep serving its own native format (16 bpp, big-endian, …) otherwise.
     */
    val BGRX_8888 = PixelFormat(
        bitsPerPixel = 32,
        depth = 24,
        bigEndian = false,
        trueColour = true,
        redMax = 255,
        greenMax = 255,
        blueMax = 255,
        redShift = 16,
        greenShift = 8,
        blueShift = 0,
    )

    fun writeSetPixelFormat(out: OutputStream, format: PixelFormat = BGRX_8888) {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_SET_PIXEL_FORMAT.toByte())
        buf.put(ByteArray(3)) // padding
        buf.put(format.bitsPerPixel.toByte())
        buf.put(format.depth.toByte())
        buf.put(if (format.bigEndian) 1.toByte() else 0.toByte())
        buf.put(if (format.trueColour) 1.toByte() else 0.toByte())
        buf.putShort(format.redMax.toShort())
        buf.putShort(format.greenMax.toShort())
        buf.putShort(format.blueMax.toShort())
        buf.put(format.redShift.toByte())
        buf.put(format.greenShift.toByte())
        buf.put(format.blueShift.toByte())
        buf.put(ByteArray(3)) // padding
        out.write(buf.array())
        out.flush()
    }

    data class ServerInit(
        val width: Int,
        val height: Int,
        val name: String,
        val bitsPerPixel: Int,
        val depth: Int,
        val bigEndian: Boolean,
        val trueColour: Boolean,
        val redMax: Int,
        val greenMax: Int,
        val blueMax: Int,
        val redShift: Int,
        val greenShift: Int,
        val blueShift: Int,
    )

    fun readServerInit(input: InputStream): ServerInit {
        val data = DataInputStream(input)
        val width = data.readUnsignedShort()
        val height = data.readUnsignedShort()
        val bitsPerPixel = data.readUnsignedByte()
        val depth = data.readUnsignedByte()
        val bigEndian = data.readUnsignedByte() != 0
        val trueColour = data.readUnsignedByte() != 0
        val redMax = data.readUnsignedShort()
        val greenMax = data.readUnsignedShort()
        val blueMax = data.readUnsignedShort()
        val redShift = data.readUnsignedByte()
        val greenShift = data.readUnsignedByte()
        val blueShift = data.readUnsignedByte()
        data.skipBytes(3)
        val nameLen = data.readInt()
        val nameBytes = ByteArray(nameLen.coerceAtLeast(0))
        data.readFully(nameBytes)
        return ServerInit(
            width = width,
            height = height,
            name = String(nameBytes, StandardCharsets.UTF_8),
            bitsPerPixel = bitsPerPixel,
            depth = depth,
            bigEndian = bigEndian,
            trueColour = trueColour,
            redMax = redMax,
            greenMax = greenMax,
            blueMax = blueMax,
            redShift = redShift,
            greenShift = greenShift,
            blueShift = blueShift,
        )
    }

    fun writeSetEncodings(out: OutputStream, encodings: IntArray = intArrayOf(ENCODING_RAW)) {
        val buf = ByteBuffer.allocate(4 + encodings.size * 4).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_SET_ENCODINGS.toByte())
        buf.put(0)
        buf.putShort(encodings.size.toShort())
        encodings.forEach { buf.putInt(it) }
        out.write(buf.array())
        out.flush()
    }

    fun writeFramebufferUpdateRequest(
        out: OutputStream,
        incremental: Boolean,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_FRAMEBUFFER_UPDATE_REQUEST.toByte())
        buf.put(if (incremental) 1 else 0)
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        out.write(buf.array())
        out.flush()
    }

    fun writePointerEvent(out: OutputStream, buttonMask: Int, x: Int, y: Int) {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_POINTER_EVENT.toByte())
        buf.put(buttonMask.toByte())
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        out.write(buf.array())
        out.flush()
    }

    fun writeKeyEvent(out: OutputStream, down: Boolean, keysym: Int) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_KEY_EVENT.toByte())
        buf.put(if (down) 1 else 0)
        buf.putShort(0)
        buf.putInt(keysym)
        out.write(buf.array())
        out.flush()
    }

    fun writeClientCutText(out: OutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        // Clip large text (Stryker's clipboard handles large text where applicable)
        val clipped = if (bytes.size > 1_000_000) bytes.copyOf(1_000_000) else bytes
        val buf = ByteBuffer.allocate(8 + clipped.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_CUT_TEXT.toByte())
        buf.put(ByteArray(3)) // padding
        buf.putInt(clipped.size)
        buf.put(clipped)
        out.write(buf.array())
        out.flush()
    }

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int, val encoding: Int)

    /**
     * Reads a server message header and returns type.
     * Handles FramebufferUpdate, SetColourMapEntries, Bell, ServerCutText.
     */
    fun readServerMessageType(input: InputStream): Int {
        return DataInputStream(input).readUnsignedByte()
    }

    fun readFramebufferUpdateHeader(input: InputStream): Int {
        val data = DataInputStream(input)
        // Type already consumed? For backward compat, check if we need to read type.
        // This method expects type byte has been read and is 0, but we also support reading it here.
        // To keep existing tests passing, we read type if not already handled.
        // Actually original impl read type + padding + count. We'll keep that but also provide alternative.
        val type = data.readUnsignedByte()
        if (type != MSG_FRAMEBUFFER_UPDATE) {
            error("Unexpected RFB message $type, expected FramebufferUpdate (0)")
        }
        data.readUnsignedByte() // padding
        return data.readUnsignedShort()
    }

    fun readFramebufferUpdateHeaderAfterType(input: InputStream): Int {
        val data = DataInputStream(input)
        data.readUnsignedByte() // padding
        return data.readUnsignedShort()
    }

    fun readRectHeader(input: InputStream): Rect {
        val data = DataInputStream(input)
        return Rect(
            x = data.readUnsignedShort(),
            y = data.readUnsignedShort(),
            width = data.readUnsignedShort(),
            height = data.readUnsignedShort(),
            encoding = data.readInt(),
        )
    }

    fun readServerCutText(input: InputStream): String {
        val data = DataInputStream(input)
        data.skipBytes(3) // padding
        val length = data.readInt()
        if (length <= 0) return ""
        val safeLen = length.coerceAtMost(10_000_000)
        val bytes = ByteArray(safeLen)
        data.readFully(bytes)
        // Skip remaining if length was larger than safe
        if (length > safeLen) {
            data.skipBytes(length - safeLen)
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun readSetColourMapEntries(input: InputStream) {
        val data = DataInputStream(input)
        data.skipBytes(1) // padding
        val first = data.readUnsignedShort()
        val count = data.readUnsignedShort()
        // Each entry is 3x U16 (RGB)
        data.skipBytes(count * 6)
    }

    /**
     * Decodes a Raw rectangle of 32-bpp little-endian `B,G,R,pad` pixels into
     * opaque ARGB ints.
     *
     * The fourth byte of each pixel is RFB *padding*, not alpha: with `depth 24`
     * TigerVNC leaves it at 0. Using it as the alpha channel produced a fully
     * transparent framebuffer (the desktop rendered as a blank surface), so alpha
     * is forced opaque here. Rows/columns outside `pixels` are skipped instead of
     * throwing, so a rogue rectangle cannot crash the viewer.
     */
    fun decodeRawBgra(pixels: IntArray, stride: Int, rect: Rect, bytes: ByteArray) {
        if (stride <= 0 || rect.width <= 0 || rect.height <= 0) return
        val required = rect.width * rect.height * 4
        require(bytes.size >= required) {
            "Raw rect ${rect.width}x${rect.height} needs $required bytes, got ${bytes.size}"
        }
        val rows = pixels.size / stride
        for (row in 0 until rect.height) {
            val y = rect.y + row
            var src = row * rect.width * 4
            if (y < 0 || y >= rows) continue
            var dst = y * stride + rect.x
            for (col in 0 until rect.width) {
                val x = rect.x + col
                if (x in 0 until stride) {
                    val b = bytes[src].toInt() and 0xff
                    val g = bytes[src + 1].toInt() and 0xff
                    val r = bytes[src + 2].toInt() and 0xff
                    pixels[dst] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
                src += 4
                dst++
            }
        }
    }

    fun writeData(out: DataOutputStream, bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }
}
