#!/usr/bin/env python3
"""Small, standards-compliant RFB 3.8 server used when Xvnc is unavailable.

This server deliberately implements only SecurityType None and Raw encoding.  It
is a fallback desktop (not an X server), but its wire format is the same one the
Android viewer negotiates with TigerVNC.
"""
import argparse
import os
import signal
import socket
import struct
import sys
import threading
from typing import Optional

VERSION = b"RFB 003.008\n"
# The Android decoder requests little-endian 32-bit BGRX: B,G,R,unused.
PIXEL_FORMAT = struct.pack("!BBBBHHHBBB3x", 32, 24, 0, 1, 255, 255, 255, 16, 8, 0)


def recv_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("RFB peer closed the connection")
        data.extend(chunk)
    return bytes(data)


class VncServer:
    def __init__(self, width: int = 800, height: int = 600, port: int = 5900,
                 password: Optional[str] = None):
        self.width, self.height, self.port = width, height, port
        self.password = password  # retained for CLI compatibility; None is the only fallback auth
        self.running = False
        self.server_socket: Optional[socket.socket] = None
        self.framebuffer = bytearray(width * height * 4)
        self._draw_desktop()

    def _pixel(self, r: int, g: int, b: int) -> bytes:
        return bytes((b & 255, g & 255, r & 255, 0))

    def _draw_desktop(self) -> None:
        for y in range(self.height):
            for x in range(self.width):
                r = 30 + (x * 25 // max(1, self.width))
                g = 30 + (y * 25 // max(1, self.height))
                self.framebuffer[(y * self.width + x) * 4:(y * self.width + x + 1) * 4] = self._pixel(r, g, 50)
        # A visible title bar makes a successful fallback update obvious.
        for y in range(min(32, self.height)):
            for x in range(self.width):
                self.framebuffer[(y * self.width + x) * 4:(y * self.width + x + 1) * 4] = self._pixel(40, 40, 140)

    def _handle_client(self, conn: socket.socket, address) -> None:
        try:
            conn.settimeout(30)
            conn.sendall(VERSION)
            peer_version = recv_exact(conn, 12)
            if not peer_version.startswith(b"RFB "):
                raise ValueError("peer did not send an RFB protocol version")

            # RFB 3.8: one byte count followed by one-byte security types.
            conn.sendall(b"\x01\x01")
            selected = recv_exact(conn, 1)[0]
            if selected != 1:
                raise ValueError(f"unsupported security type {selected}")
            # SecurityResult belongs immediately after the selected type.
            conn.sendall(struct.pack("!I", 0))

            # ClientInit precedes ServerInit (and ServerInit has no length prefix).
            shared = recv_exact(conn, 1)[0]
            del shared
            name = b"Lenix Python RFB fallback"
            server_init = (struct.pack("!HH", self.width, self.height) + PIXEL_FORMAT +
                           struct.pack("!I", len(name)) + name)
            conn.sendall(server_init)

            while self.running:
                msg_type = recv_exact(conn, 1)[0]
                if msg_type == 0:  # SetPixelFormat: 3 pad + 16-byte format
                    recv_exact(conn, 19)
                elif msg_type == 1:  # FixColourMapEntries
                    recv_exact(conn, 5)
                    count = struct.unpack("!H", recv_exact(conn, 2))[0]
                    recv_exact(conn, count * 6)
                elif msg_type == 2:  # SetEncodings
                    recv_exact(conn, 3)
                    count = struct.unpack("!H", recv_exact(conn, 2))[0]
                    recv_exact(conn, count * 4)
                elif msg_type == 3:  # FramebufferUpdateRequest
                    incremental, x, y, w, h = struct.unpack("!BHHHH", recv_exact(conn, 9))
                    del incremental
                    self._send_update(conn, x, y, w, h)
                elif msg_type == 4:  # KeyEvent
                    recv_exact(conn, 7)
                elif msg_type == 5:  # PointerEvent
                    recv_exact(conn, 5)
                elif msg_type == 6:  # ClientCutText
                    recv_exact(conn, 3)
                    length = struct.unpack("!I", recv_exact(conn, 4))[0]
                    recv_exact(conn, length)
                else:
                    raise ValueError(f"unsupported client message {msg_type}")
        except (OSError, ConnectionError, ValueError) as exc:
            print(f"RFB client {address} ended: {exc}", file=sys.stderr, flush=True)
        finally:
            conn.close()

    def _send_update(self, conn: socket.socket, x: int, y: int, w: int, h: int) -> None:
        # Clamp the rectangle and describe exactly the bytes that follow it.
        x = max(0, min(x, self.width))
        y = max(0, min(y, self.height))
        w = max(0, min(w, self.width - x))
        h = max(0, min(h, self.height - y))
        conn.sendall(struct.pack("!BBH", 0, 0, 1) + struct.pack("!HHHHI", x, y, w, h, 0))
        for row in range(y, y + h):
            start = (row * self.width + x) * 4
            conn.sendall(self.framebuffer[start:start + w * 4])

    def start(self) -> None:
        self.running = True
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(("0.0.0.0", self.port))
        self.server_socket.listen(5)
        self.server_socket.settimeout(1)
        print(f"Lenix Python RFB 3.8 fallback listening on 0.0.0.0:{self.port} ({self.width}x{self.height})", flush=True)
        while self.running:
            try:
                conn, address = self.server_socket.accept()
                threading.Thread(target=self._handle_client, args=(conn, address), daemon=True).start()
            except socket.timeout:
                continue
            except OSError as exc:
                if self.running:
                    print(f"VNC accept failed: {exc}", file=sys.stderr, flush=True)
                break
        self.stop()

    def stop(self, *_args) -> None:
        self.running = False
        if self.server_socket:
            self.server_socket.close()
            self.server_socket = None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("-p", "--port", type=int, default=5900)
    parser.add_argument("-w", "--width", type=int, default=800)
    parser.add_argument("-H", "--height", type=int, default=600)
    parser.add_argument("--password")
    args = parser.parse_args()
    server = VncServer(args.width, args.height, args.port, args.password)
    signal.signal(signal.SIGTERM, server.stop)
    signal.signal(signal.SIGINT, server.stop)
    try:
        server.start()
    except OSError as exc:
        print(f"VNC server could not listen on port {args.port}: {exc}", file=sys.stderr, flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
