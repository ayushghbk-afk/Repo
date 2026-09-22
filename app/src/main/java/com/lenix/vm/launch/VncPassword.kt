package com.lenix.vm.launch

import java.io.File
import java.security.SecureRandom

/**
 * Per-boot RFB password: 12 hex chars, file mode owner-only (ARCHITECTURE.md §10).
 */
object VncPassword {

    const val LENGTH = 12

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(LENGTH / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    fun write(file: File, password: String) {
        file.parentFile?.mkdirs()
        file.writeText(password)
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
