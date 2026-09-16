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
 */
class RfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
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

    fun handshake() {
        if (host != "127.0.0.1" && host != "localhost") {
            throw VmException(VmError.VNC_CONNECTION_FAILED, "VNC is loopback-only.")
        }
        val streams = try {
            connect()
        } catch (e: Exception) {
            throw VmException(VmError.VNC_CONNECTION_FAILED, e.message, e)
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
            RfbProtocol.writeSecurityType(output, chosen)
            if (!RfbProtocol.readSecurityResult(input)) {
                throw VmException(VmError.VNC_CONNECTION_FAILED, "RFB security handshake failed.")
            }
            RfbProtocol.writeClientInit(output, shared = true)
            server = RfbProtocol.readServerInit(input)
            pixels = IntArray(server.width * server.height)
            // Pin the wire format the Raw decoder understands (ARCHITECTURE.md §8.2
            // "PixelFormat negotiation"). Without this the server keeps serving its
            // native format and decodeRawBgra reinterprets it as 32-bpp BGRX.
            RfbProtocol.writeSetPixelFormat(output, RfbProtocol.BGRX_8888)
            RfbProtocol.writeSetEncodings(output)
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
            throw VmException(VmError.VNC_CONNECTION_FAILED, e.message, e)
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
        RfbProtocol.writePointerEvent(output, buttonMask, x, y)
    }

    fun key(down: Boolean, keysym: Int) {
        RfbProtocol.writeKeyEvent(output, down, keysym)
    }

    /** Returns a copy suitable for handing to a UI thread after [readUpdate]. */
    fun snapshotPixels(): IntArray = pixels.copyOf()

    fun readUpdate() {
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
