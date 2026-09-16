package com.lenix.neolang.frontend

import com.lenix.neolang.runtime.LenixLangValue

/**
 * NeoLang tokens — ported from Stryker for Lenix.
 */
class LenixLangEOFToken : LenixLangToken(LenixLangTokenType.EOF, LenixLangTokenValue.EOF)

open class LenixLangToken(val tokenType: LenixLangTokenType, val tokenValue: LenixLangTokenValue) {
    var lineNumber = 0

    override fun toString(): String = "Token { tokenType: $tokenType, tokenValue: $tokenValue }"
}

enum class LenixLangTokenType {
    NUMBER,
    ID,
    STRING,
    BRACKET_START,
    BRACKET_END,
    ARRAY_START,
    ARRAY_END,
    COLON,
    COMMA,
    EOL,
    EOF,
}

class LenixLangTokenValue(val value: LenixLangValue) {
    override fun toString(): String = value.asString()

    companion object {
        val COLON = LenixLangTokenValue(LenixLangValue(":"))
        val COMMA = LenixLangTokenValue(LenixLangValue(","))
        val QUOTE = LenixLangTokenValue(LenixLangValue("\""))
        val EOF = LenixLangTokenValue(LenixLangValue("<EOF>"))

        val BRACKET_START = LenixLangTokenValue(LenixLangValue("{"))
        val BRACKET_END = LenixLangTokenValue(LenixLangValue("}"))
        val ARRAY_START = LenixLangTokenValue(LenixLangValue("["))
        val ARRAY_END = LenixLangTokenValue(LenixLangValue("]"))

        fun wrap(tokenText: String): LenixLangTokenValue {
            return when (tokenText) {
                COLON.value.asString() -> COLON
                COMMA.value.asString() -> COMMA
                QUOTE.value.asString() -> QUOTE
                BRACKET_START.value.asString() -> BRACKET_START
                BRACKET_END.value.asString() -> BRACKET_END
                ARRAY_START.value.asString() -> ARRAY_START
                ARRAY_END.value.asString() -> ARRAY_END
                else -> LenixLangTokenValue(LenixLangValue(tokenText))
            }
        }
    }
}
