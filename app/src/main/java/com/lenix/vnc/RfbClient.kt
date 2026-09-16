package com.lenix.vnc

import com.lenix.vm.VmError
import com.lenix.vm.VmException
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * Loopback RFB 3.8 client. Connects only to 127.0.0.1 (ARCHITECTURE.md §8).
 *
 * Improved from Stryker reference:
 * - Handles ServerCutText (clipboard) for Android ↔ Linux sync
 * - Handles Bell and SetColourMapEntries gracefully
 * - Clear diagnostics for VNC auth vs None
 * - Supports DesktopSize pseudo-encoding for resize
 * - Properly handles multiple message types in read loop
 */
class RfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val vncPassword: String? = null,
    private val onClipboardText: ((String) -> Unit)? = null,
    private val connect: () -> Pair<InputStream, OutputStream> = {
        val socket = Socket(InetAddress.getByName(host), port)
        socket.tcpNoDelay = true
        socket.getInputStream() to socket.getOutputStream()
    },
) : Closeable {
    lateinit var server: RfbProtocol.ServerInit
        private set

    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    var pixels: IntArray = IntArray(0)
        private set

    // For clipboard handling, keep last sent/received to avoid echo loops
    private var lastClipboardSent: String? = null
    private var lastClipboardReceived: String? = null

    fun handshake() {
        if (host != "127.0.0.1" && host != "localhost") {
            throw VmException(VmError.VNC_CONNECTION_FAILED, "VNC is loopback-only (tried $host).")
        }
        val streams = try {
            connect()
        } catch (e: Exception) {
            throw VmException(
                VmError.VNC_CONNECTION_FAILED,
                "Could not connect to VNC server at 127.0.0.1:$port: ${e.message}. " +
                    "Check that Xvnc/Xvfb+x11vnc started (see /tmp/xvnc.log in guest).",
                e,
            )
        }
        input = streams.first
        output = streams.second
        try {
            val version = RfbProtocol.readVersion(input)
            if (!version.startsWith("RFB ")) {
                throw VmException(VmError.VNC_CONNECTION_FAILED, "Not an RFB server: $version")
            }
            RfbProtocol.writeVersion(output)
            val types = RfbProtocol.readSecurityTypes(input)
            val chosen = RfbProtocol.chooseSecurity(types)

            if (chosen == RfbProtocol.SECURITY_VNC) {
                // Lenix default is None, but if server requires VNC auth (Stryker's x11vnc does),
                // we need to give a clear diagnostic. Full DES challenge is not implemented
                // in this build to keep dependencies minimal; we fail with actionable message.
                if (vncPassword.isNullOrEmpty()) {
                    throw VmException(
                        VmError.VNC_CONNECTION_FAILED,
                        "RFB server at 127.0.0.1:$port requires VNC authentication (SecurityType 2) " +
                            "but no password was provided. If using x11vnc, set password via vncpasswd or use " +
                            "-SecurityTypes None for loopback. Lenix default is None.",
                    )
                }
                // For now, even with password, we don't implement DES — give clear error
                // The proper fix would be to implement VNC DES auth or switch server to None
                throw VmException(
                    VmError.VNC_CONNECTION_FAILED,
                    "RFB VNC auth (SecurityType 2) requested but DES challenge not implemented in this build. " +
                        "Start Xvnc with -SecurityTypes None for loopback, or Xvfb+x11vnc with -nopw. " +
                        "Server offered types: $types",
                )
            }

            RfbProtocol.writeSecurityType(output, chosen)
            if (!RfbProtocol.readSecurityResult(input)) {
                throw VmException(
                    VmError.VNC_CONNECTION_FAILED,
                    "RFB security handshake failed for 127.0.0.1:$port. Server may have rejected security type $chosen. " +
                        "Offered: $types. Check /tmp/xvnc.log /tmp/x11vnc.log in guest.",
                )
            }
            RfbProtocol.writeClientInit(output, shared = true)
            server = RfbProtocol.readServerInit(input)
            if (server.width <= 0 || server.height <= 0 || server.width > 8192 || server.height > 8192) {
                throw VmException(
                    VmError.VNC_CONNECTION_FAILED,
                    "RFB ServerInit has invalid dimensions ${server.width}x${server.height}. " +
                        "Check X server startup logs.",
                )
            }
            pixels = IntArray(server.width * server.height)
            // Pin the wire format the Raw decoder understands (ARCHITECTURE.md §8.2
            // "PixelFormat negotiation"). Without this the server keeps serving its
            // native format and decodeRawBgra reinterprets it as 32-bpp BGRX.
            RfbProtocol.writeSetPixelFormat(output, RfbProtocol.BGRX_8888)
            // Request Raw + DesktopSize pseudo-encoding for resize handling
            RfbProtocol.writeSetEncodings(
                output,
                intArrayOf(RfbProtocol.ENCODING_RAW, RfbProtocol.ENCODING_DESKTOP_SIZE),
            )
            RfbProtocol.writeFramebufferUpdateRequest(
                output,
                incremental = false,
                x = 0,
                y = 0,
                width = server.width,
                height = server.height,
            )
        } catch (e: VmException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw VmException(
                VmError.VNC_CONNECTION_FAILED,
                "RFB handshake failed for 127.0.0.1:$port: ${e.message}. " +
                    "Check guest logs /tmp/xvnc.log /tmp/session.log",
                e,
            )
        }
    }

    /**
     * Releases the loopback socket. A failed handshake used to leave the socket
     * dangling, so the viewer's retry loop leaked one connection per attempt.
     */
    override fun close() {
        if (::input.isInitialized) runCatching { input.close() }
        if (::output.isInitialized) runCatching { output.close() }
    }

    fun requestUpdate(incremental: Boolean = true) {
        if (!::server.isInitialized) return
        RfbProtocol.writeFramebufferUpdateRequest(
            output,
            incremental,
            0,
            0,
            server.width,
            server.height,
        )
    }

    fun pointer(buttonMask: Int, x: Int, y: Int) {
        if (!::output.isInitialized) return
        // Clamp to server size to avoid out-of-bounds
        val cx = x.coerceIn(0, server.width - 1)
        val cy = y.coerceIn(0, server.height - 1)
        RfbProtocol.writePointerEvent(output, buttonMask, cx, cy)
    }

    fun key(down: Boolean, keysym: Int) {
        if (!::output.isInitialized) return
        RfbProtocol.writeKeyEvent(output, down, keysym)
    }

    fun sendClipboardText(text: String) {
        if (!::output.isInitialized) return
        if (text == lastClipboardReceived) return // avoid echo loop
        lastClipboardSent = text
        RfbProtocol.writeClientCutText(output, text)
    }

    /** Returns a copy suitable for handing to a UI thread after [readUpdate]. */
    fun snapshotPixels(): IntArray = pixels.copyOf()

    /**
     * Reads next server message and handles it.
     * Returns true if framebuffer was updated, false otherwise (e.g. clipboard, bell).
     * Handles multiple message types like Stryker's VNC implementation should.
     */
    fun readUpdate(): Boolean {
        if (!::input.isInitialized) throw VmException(VmError.VNC_CONNECTION_FAILED, "RFB not connected")
        val data = java.io.DataInputStream(input)
        val msgType = data.readUnsignedByte()

        return when (msgType) {
            RfbProtocol.MSG_FRAMEBUFFER_UPDATE -> {
                data.readUnsignedByte() // padding
                val count = data.readUnsignedShort()
                var updated = false
                repeat(count) {
                    val rect = RfbProtocol.readRectHeader(input)
                    when (rect.encoding) {
                        RfbProtocol.ENCODING_RAW -> {
                            val bytes = ByteArray(rect.width * rect.height * 4)
                            var off = 0
                            while (off < bytes.size) {
                                val n = input.read(bytes, off, bytes.size - off)
                                if (n < 0) throw VmException(VmError.VNC_CONNECTION_FAILED, "RFB stream ended during Raw rect.")
                                off += n
                            }
                            RfbProtocol.decodeRawBgra(pixels, server.width, rect, bytes)
                            updated = true
                        }
                        RfbProtocol.ENCODING_DESKTOP_SIZE -> {
                            // Desktop resize pseudo-encoding — rect header contains new size in width/height
                            // Server should have sent new ServerInit? Actually DesktopSize uses rect dimensions as new size.
                            // For simplicity, we treat it as resize request and reallocate.
                            if (rect.width > 0 && rect.height > 0 && rect.width <= 8192 && rect.height <= 8192) {
                                val newSize = rect.width * rect.height
                                if (newSize != pixels.size) {
                                    pixels = IntArray(newSize)
                                    server = server.copy(width = rect.width, height = rect.height)
                                }
                            }
                            updated = true
                        }
                        else -> {
                            throw VmException(
                                VmError.VNC_CONNECTION_FAILED,
                                "Unsupported encoding ${rect.encoding} for rect ${rect.width}x${rect.height} at ${rect.x},${rect.y}. " +
                                    "This build decodes Raw only. Server should have honored SetEncodings(Raw).",
                            )
                        }
                    }
                }
                updated
            }
            RfbProtocol.MSG_SERVER_CUT_TEXT -> {
                val text = RfbProtocol.readServerCutText(input)
                if (text != lastClipboardSent) {
                    lastClipboardReceived = text
                    onClipboardText?.invoke(text)
                }
                false
            }
            RfbProtocol.MSG_BELL -> {
                // Bell — ignore, but could trigger haptic
                false
            }
            RfbProtocol.MSG_SET_COLOUR_MAP_ENTRIES -> {
                RfbProtocol.readSetColourMapEntries(input)
                false
            }
            else -> {
                throw VmException(
                    VmError.VNC_CONNECTION_FAILED,
                    "Unexpected RFB server message type $msgType. Expected FramebufferUpdate (0), Bell (2), or ServerCutText (3).",
                )
            }
        }
    }

    /**
     * Legacy method for backward compat — reads FramebufferUpdate header assuming type byte included.
     * New code should use readUpdate() which handles all message types.
     */
    @Deprecated("Use readUpdate() which handles all server message types")
    fun readUpdateLegacy() {
        val count = RfbProtocol.readFramebufferUpdateHeader(input)
        repeat(count) {
            val rect = RfbProtocol.readRectHeader(input)
            if (rect.encoding != RfbProtocol.ENCODING_RAW) {
                throw VmException(
                    VmError.VNC_CONNECTION_FAILED,
                    "Unsupported encoding ${rect.encoding}; this build decodes Raw only.",
                )
            }
            val bytes = ByteArray(rect.width * rect.height * 4)
            var off = 0
            while (off < bytes.size) {
                val n = input.read(bytes, off, bytes.size - off)
                if (n < 0) throw VmException(VmError.VNC_CONNECTION_FAILED, "RFB stream ended.")
                off += n
            }
            RfbProtocol.decodeRawBgra(pixels, server.width, rect, bytes)
        }
    }
}
