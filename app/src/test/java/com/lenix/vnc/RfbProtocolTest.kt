package com.lenix.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RfbProtocolTest {

    @Test
    fun `version round trip`() {
        val out = ByteArrayOutputStream()
        RfbProtocol.writeVersion(out)
        assertEquals(RfbProtocol.VERSION, String(out.toByteArray()))
        assertEquals(
            RfbProtocol.VERSION,
            RfbProtocol.readVersion(ByteArrayInputStream(out.toByteArray())),
        )
    }

    @Test
    fun `chooseSecurity prefers none`() {
        assertEquals(
            RfbProtocol.SECURITY_NONE,
            RfbProtocol.chooseSecurity(listOf(RfbProtocol.SECURITY_VNC, RfbProtocol.SECURITY_NONE)),
        )
    }

    @Test
    fun `readServerInit parses pixel format and name`() {
        val buf = ByteBuffer.allocate(24 + 4 + 5).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(1280)
        buf.putShort(720)
        buf.put(32.toByte())
        buf.put(24.toByte())
        buf.put(0.toByte())
        buf.put(1.toByte())
        buf.putShort(255)
        buf.putShort(255)
        buf.putShort(255)
        buf.put(16.toByte())
        buf.put(8.toByte())
        buf.put(0.toByte())
        buf.put(ByteArray(3))
        buf.putInt(5)
        buf.put("Lenix".toByteArray())
        val init = RfbProtocol.readServerInit(ByteArrayInputStream(buf.array()))
        assertEquals(1280, init.width)
        assertEquals(720, init.height)
        assertEquals("Lenix", init.name)
        assertEquals(32, init.bitsPerPixel)
        assertTrue(init.trueColour)
    }

    @Test
    fun `decodeRawBgra writes opaque ARGB and ignores the RFB padding byte`() {
        val pixels = IntArray(4)
        // 4th byte of each pixel is RFB padding (0 with depth 24), never alpha.
        val bytes = byteArrayOf(
            0x11, 0x22, 0x33, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x03, 0x00,
            0x0A, 0x0B, 0x0C, 0x00,
        )
        val rect = RfbProtocol.Rect(0, 0, 2, 2, RfbProtocol.ENCODING_RAW)
        RfbProtocol.decodeRawBgra(pixels, 2, rect, bytes)
        assertEquals(0xFF332211.toInt(), pixels[0])
        assertEquals(0xFF000000.toInt(), pixels[1])
        assertEquals(0xFF030201.toInt(), pixels[2])
        assertEquals(0xFF0C0B0A.toInt(), pixels[3])
        // Every pixel must be fully opaque, otherwise the desktop renders blank.
        assertTrue(pixels.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `decodeRawBgra places a sub-rectangle at its offset`() {
        val pixels = IntArray(4)
        val rect = RfbProtocol.Rect(1, 1, 1, 1, RfbProtocol.ENCODING_RAW)
        RfbProtocol.decodeRawBgra(pixels, 2, rect, byteArrayOf(0x11, 0x22, 0x33, 0x00))
        assertEquals(0, pixels[0])
        assertEquals(0xFF332211.toInt(), pixels[3])
    }

    @Test
    fun `decodeRawBgra clips rectangles that fall outside the framebuffer`() {
        val pixels = IntArray(4)
        val rect = RfbProtocol.Rect(1, 1, 2, 2, RfbProtocol.ENCODING_RAW)
        RfbProtocol.decodeRawBgra(pixels, 2, rect, ByteArray(2 * 2 * 4) { 0x7F })
        assertEquals(0xFF7F7F7F.toInt(), pixels[3])
    }

    @Test
    fun `setPixelFormat pins 32bpp little-endian BGRX`() {
        val out = ByteArrayOutputStream()
        RfbProtocol.writeSetPixelFormat(out)
        val bytes = out.toByteArray()
        assertEquals(20, bytes.size)
        assertEquals(RfbProtocol.CLIENT_SET_PIXEL_FORMAT.toByte(), bytes[0])
        assertEquals(32.toByte(), bytes[4]) // bits-per-pixel
        assertEquals(24.toByte(), bytes[5]) // depth
        assertEquals(0.toByte(), bytes[6]) // big-endian-flag
        assertEquals(1.toByte(), bytes[7]) // true-colour-flag
        assertEquals(255, ByteBuffer.wrap(bytes, 8, 2).order(ByteOrder.BIG_ENDIAN).short.toInt())
        assertEquals(16.toByte(), bytes[14]) // red-shift
        assertEquals(8.toByte(), bytes[15]) // green-shift
        assertEquals(0.toByte(), bytes[16]) // blue-shift
    }

    @Test
    fun `pointer event is six bytes`() {
        val out = ByteArrayOutputStream()
        RfbProtocol.writePointerEvent(out, 1, 10, 20)
        val bytes = out.toByteArray()
        assertEquals(6, bytes.size)
        assertEquals(RfbProtocol.CLIENT_POINTER_EVENT.toByte(), bytes[0])
        assertEquals(1.toByte(), bytes[1])
    }

    @Test
    fun `handshake against a scripted server`() {
        val serverOut = ByteArrayOutputStream()
        // What the client will read: version, 1 security type (None), result 0, server-init
        serverOut.write(RfbProtocol.VERSION.toByteArray())
        serverOut.write(byteArrayOf(1, RfbProtocol.SECURITY_NONE.toByte()))
        serverOut.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array())
        val init = ByteBuffer.allocate(24 + 4 + 1).order(ByteOrder.BIG_ENDIAN)
        init.putShort(2).putShort(2)
        init.put(32.toByte()).put(24.toByte()).put(0.toByte()).put(1.toByte())
        init.putShort(255).putShort(255).putShort(255)
        init.put(16.toByte()).put(8.toByte()).put(0.toByte())
        init.put(ByteArray(3))
        init.putInt(1).put('X'.code.toByte())
        serverOut.write(init.array())

        val clientWrites = ByteArrayOutputStream()
        val client = RfbClient(port = 5901, connect = {
            ByteArrayInputStream(serverOut.toByteArray()) to clientWrites
        })
        client.handshake()
        assertEquals(2, client.server.width)
        assertEquals("X", client.server.name)
        val written = clientWrites.toByteArray()
        assertTrue(String(written).startsWith("RFB "))
        // ProtocolVersion(12) + security type(1) + ClientInit(1) = 14 bytes, then
        // SetPixelFormat must be negotiated before SetEncodings (ARCHITECTURE.md 8.2).
        assertEquals(RfbProtocol.CLIENT_SET_PIXEL_FORMAT.toByte(), written[14])
        assertEquals(32.toByte(), written[18]) // 32 bpp, the only format we decode
        assertEquals(0.toByte(), written[20]) // little-endian
        assertEquals(RfbProtocol.CLIENT_SET_ENCODINGS.toByte(), written[34])
        assertEquals(
            RfbProtocol.CLIENT_FRAMEBUFFER_UPDATE_REQUEST.toByte(),
            // SetEncodings is 4 header bytes + 4 per encoding (RFC 6143 §7.5.2):
            // with Raw + DesktopSize that is 12 bytes, so the update request
            // starts at 34 + 12.
            written[34 + 12],
        )
    }
}
