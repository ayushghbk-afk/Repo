package com.lenix.neolang

import com.lenix.neolang.frontend.LenixLangLexer
import com.lenix.neolang.frontend.LenixLangParser
import com.lenix.neolang.frontend.AstVisitor
import com.lenix.neolang.frontend.ConfigVisitor
import com.lenix.neolang.runtime.LenixLangContext
import android.util.Log

/**
 * NeoLang bridge — provides config file parsing for Lenix, ported from Stryker.
 *
 * Stryker uses NeoLang for:
 * - Profile configuration (color schemes, font sizes)
 * - Terminal settings
 * - Display process configuration
 *
 * Lenix uses it for:
 * - Instance profiles
 * - Desktop environment configuration
 * - Color schemes
 *
 * Example NeoLang config:
 *   profile: {
 *     name: "Default"
 *     color: [ 0, 0, 0 ]
 *     fontSize: 14
 *   }
 */
object NeoLangBridge {
    private const val TAG = "LenixNeoLang"

    fun parseConfig(configText: String): LenixLangContext? {
        return try {
            val lexer = LenixLangLexer().apply { setInputSource(configText) }
            val tokens = lexer.lex()

            val parser = LenixLangParser().apply { setInputSource(configText) }
            val ast = parser.parse()

            val callback = ConfigVisitor()
            val visitor = AstVisitor(ast, callback)
            visitor.start()
            callback.getRootContext()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse NeoLang config: ${e.message}", e)
            null
        }
    }

    fun parseConfigFile(file: java.io.File): LenixLangContext? {
        if (!file.isFile) return null
        return try {
            parseConfig(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read config file ${file.absolutePath}: ${e.message}", e)
            null
        }
    }

    fun createDefaultProfile(): LenixLangContext {
        val defaultConfig = """
            profile: {
                name: "Default"
                fontSize: 14
                colorScheme: "dark"
            }
            terminal: {
                fontSize: 14
                cursorStyle: "block"
                bell: false
            }
            desktop: {
                resolution: "1280x720"
                desktop: "openbox"
            }
        """.trimIndent()
        return parseConfig(defaultConfig) ?: LenixLangContext("root")
    }
}
