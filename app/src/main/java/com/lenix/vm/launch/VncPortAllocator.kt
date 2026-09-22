package com.lenix.vm.launch

import java.net.InetAddress
import java.net.ServerSocket

/**
 * Picks a free loopback RFB port in 5901–5999 (ARCHITECTURE.md §7.2).
 *
 * Binding is probed with `SO_REUSEADDR` off so a live Xvnc keeps the port.
 */
object VncPortAllocator {

    const val FIRST = 5901
    const val LAST = 5999

    fun allocate(bindProbe: (Int) -> Boolean = ::canBind): Int {
        for (port in FIRST..LAST) {
            if (bindProbe(port)) return port
        }
        error("No free VNC port in $FIRST–$LAST")
    }

    fun canBind(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
    } catch (_: Exception) {
        false
    }
}
