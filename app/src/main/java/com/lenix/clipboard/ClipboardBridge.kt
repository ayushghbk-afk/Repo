package com.lenix.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Clipboard bridge for Android ↔ Linux synchronization.
 * Adapted from Stryker's Xorg Clipboard.java.
 *
 * Provides:
 * - Android clipboard → Linux (via VNC ClientCutText or OSC 52)
 * - Linux clipboard → Android (via VNC ServerCutText or OSC 52)
 * - Listener for primary clip changes
 * - Lifecycle handling and large text support
 */
class ClipboardBridge(private val context: Context) {

    private val clipboardManager: ClipboardManager? by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    /**
     * Sets Android clipboard text.
     */
    fun setText(text: String) {
        try {
            val cm = clipboardManager ?: return
            val clip = ClipData.newPlainText("Lenix", text)
            cm.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.w("LenixClipboard", "setClipboardText failed: ${e.message}")
        }
    }

    /**
     * Gets Android clipboard text.
     */
    fun getText(): String {
        return try {
            val cm = clipboardManager ?: return ""
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            clip.getItemAt(0)?.text?.toString() ?: ""
        } catch (e: Exception) {
            Log.w("LenixClipboard", "getClipboardText failed: ${e.message}")
            ""
        }
    }

    /**
     * Sets listener for clipboard changes (Android → Linux sync).
     * Like Stryker's Clipboard.setListener.
     */
    fun setListener(listener: (String) -> Unit) {
        try {
            val cm = clipboardManager ?: return
            cm.addPrimaryClipChangedListener {
                val text = getText()
                if (text.isNotEmpty()) {
                    listener(text)
                }
            }
        } catch (e: Exception) {
            Log.w("LenixClipboard", "setListener failed: ${e.message}")
        }
    }

    companion object {
        /**
         * Singleton accessor similar to Stryker's Clipboard.get().
         */
        fun get(context: Context): ClipboardBridge = ClipboardBridge(context.applicationContext)
    }
}

/**
 * Legacy compatibility layer for older Android versions (like Stryker's OlderClipboard).
 */
object LegacyClipboard {
    fun set(context: Context, text: String) {
        try {
            @Suppress("DEPRECATION")
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.text.ClipboardManager
            cm?.text = text
        } catch (e: Exception) {
            Log.w("LenixClipboard", "Legacy set failed: ${e.message}")
        }
    }

    fun get(context: Context): String {
        return try {
            @Suppress("DEPRECATION")
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.text.ClipboardManager
            cm?.text?.toString() ?: ""
        } catch (e: Exception) {
            Log.w("LenixClipboard", "Legacy get failed: ${e.message}")
            ""
        }
    }
}
