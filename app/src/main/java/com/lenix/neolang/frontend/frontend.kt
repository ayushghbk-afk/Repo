package com.lenix.neolang.frontend

/**
 * NeoLang lexer and parser — ported from Stryker for Lenix.
 * Provides scripting/configuration language for Lenix profiles, color schemes, etc.
 */

class LenixLangLexer {
    private var programCode: String? = null
    private var currentPosition: Int = 0
    private var currentChar: Char = ' '
    private var lineNumber = 0

    internal fun setInputSource(programCode: String?) {
        this.programCode = programCode
    }

    internal fun lex(): List<LenixLangToken> {
        val programCode = this.programCode ?: return listOf()
        val tokens = ArrayList<LenixLangToken>()
        currentPosition = 0
        lineNumber = 1

        if (programCode.isNotEmpty()) {
            currentChar = programCode[currentPosition]

            while (currentPosition < programCode.length) {
                val token = nextToken
                if (token is LenixLangEOFToken) {
                    break
                }
                tokens.add(token)
            }
        }
        return tokens
    }

    private fun moveToNextChar(eofThrow: Boolean = false): Boolean {
        val programCode = this.programCode ?: return false
        currentPosition++
        if (currentPosition >= programCode.length) {
            if (eofThrow) {
                throw InvalidTokenException("Unexpected EOF near `$currentChar' in line $lineNumber")
            }
            return false
        } else {
            currentChar = programCode[currentPosition]
            return true
        }
    }

    private val nextToken: LenixLangToken
        get() {
            val programCode = this.programCode ?: return LenixLangEOFToken()

            while (currentChar == ' ' || currentChar == '\t' || currentChar == '\n' || currentChar == '\r') {
                if (currentChar == '\n') {
                    ++lineNumber
                }
                if (!moveToNextChar()) {
                    return LenixLangEOFToken()
                }
            }

            if (currentPosition >= programCode.length) {
                return LenixLangEOFToken()
            }

            val currentToken = LenixLangTokenValue.wrap(currentChar.toString())
            val token: LenixLangToken = when (currentToken) {
                LenixLangTokenValue.COLON -> {
                    moveToNextChar(eofThrow = true)
                    LenixLangToken(LenixLangTokenType.COLON, currentToken)
                }
                LenixLangTokenValue.BRACKET_START -> {
                    moveToNextChar(eofThrow = true)
                    LenixLangToken(LenixLangTokenType.BRACKET_START, currentToken)
                }
                LenixLangTokenValue.BRACKET_END -> {
                    moveToNextChar()
                    LenixLangToken(LenixLangTokenType.BRACKET_END, currentToken)
                }
                LenixLangTokenValue.ARRAY_START -> {
                    moveToNextChar()
                    LenixLangToken(LenixLangTokenType.ARRAY_START, currentToken)
                }
                LenixLangTokenValue.ARRAY_END -> {
                    moveToNextChar()
                    LenixLangToken(LenixLangTokenType.ARRAY_END, currentToken)
                }
                LenixLangTokenValue.COMMA -> {
                    moveToNextChar(eofThrow = true)
                    LenixLangToken(LenixLangTokenType.COMMA, currentToken)
                }
                LenixLangTokenValue.QUOTE -> {
                    LenixLangToken(LenixLangTokenType.STRING, LenixLangTokenValue.wrap(getNextTokenAsString()))
                }
                else -> {
                    if (currentChar.isNumber()) {
                        LenixLangToken(LenixLangTokenType.NUMBER, LenixLangTokenValue.wrap(getNextTokenAsNumber()))
                    } else if (isIdentifier(currentChar, true)) {
                        LenixLangToken(LenixLangTokenType.ID, LenixLangTokenValue.wrap(getNextTokenAsId()))
                    } else {
                        throw InvalidTokenException("Unexpected character near line $lineNumber: $currentChar")
                    }
                }
            }

            token.lineNumber = lineNumber
            return token
        }

    private fun getNextTokenAsString(): String {
        moveToNextChar(eofThrow = true)
        val builder = StringBuilder()
        var loop = true
        while (loop && currentChar != LenixLangTokenValue.QUOTE.value.asString()[0]) {
            builder.append(currentChar)
            loop = moveToNextChar()
        }
        moveToNextChar()
        return builder.toString()
    }

    private fun getNextTokenAsNumber(): String {
        var numberValue: Double = (currentChar.code - '0'.code).toDouble()

        if (numberValue > 0) {
            numberValue = getNextDecimalNumber(numberValue)
        } else {
            if (!moveToNextChar()) {
                return numberValue.toString()
            }
            if (currentChar == 'x' || currentChar == 'X') {
                numberValue = getNextHexNumber(numberValue)
            } else if (currentChar == 'b' || currentChar == 'B') {
                numberValue = getNextBinaryNumber(numberValue)
            } else {
                numberValue = getNextOctalNumber(numberValue)
            }
        }
        return numberValue.toString()
    }

    private fun getNextOctalNumber(numberValue: Double): Double {
        var value: Double = numberValue
        var loop = true
        while (loop && currentChar in ('0'..'7')) {
            value = value * 8 + currentChar.toNumber()
            loop = moveToNextChar()
        }
        return value
    }

    private fun getNextBinaryNumber(numberValue: Double): Double {
        var value = numberValue
        var loop = moveToNextChar()
        while (loop && currentChar in ('0'..'1')) {
            value += value * 2 + currentChar.toNumber()
            loop = moveToNextChar()
        }
        return value
    }

    private fun getNextHexNumber(numberValue: Double): Double {
        var value = numberValue
        var loop = moveToNextChar()
        while (loop && (currentChar.isHexNumber())) {
            value *= 16 + (currentChar.code and 15) + if (currentChar >= 'A') 9 else 0
            loop = moveToNextChar()
        }
        return value
    }

    private fun getNextDecimalNumber(numberValue: Double): Double {
        var floatPointMeet = false
        var floatPart: Double = 0.0
        var floatNumberCounter = 1
        var value = numberValue

        var loop = moveToNextChar()
        while (loop) {
            if (currentChar.isNumber()) {
                if (floatPointMeet) {
                    floatPart = floatPart * 10 + currentChar.toNumber()
                    floatNumberCounter *= 10
                } else {
                    value = value * 10 + currentChar.toNumber()
                }
                loop = moveToNextChar()
            } else if (currentChar == '.') {
                floatPointMeet = true
                loop = moveToNextChar()
            } else {
                break
            }
        }
        return value + floatPart / floatNumberCounter
    }

    private fun getNextTokenAsId(): String {
        return buildString {
            while (isIdentifier(currentChar, false)) {
                append(currentChar)
                if (!moveToNextChar()) {
                    break
                }
            }
        }
    }

    private fun isIdentifier(tokenChar: Char, isFirstChar: Boolean): Boolean {
        val isId = (tokenChar in 'a'..'z') || (tokenChar in 'A'..'Z') || ("_-#$".contains(tokenChar))
        return if (isFirstChar) isId else (isId || (tokenChar in '0'..'9'))
    }

    private fun Char.toNumber(): Int = if (isNumber()) this.code - '0'.code else 0
    private fun Char.isNumber(): Boolean = this in ('0'..'9')
    private fun Char.isHexNumber(): Boolean = isNumber() || this in ('a'..'f') || this in ('A'..'F')
}

class LenixLangParser {
    private val lexer = LenixLangLexer()
    private var tokens = mutableListOf<LenixLangToken>()
    private var currentPosition: Int = 0
    private var currentToken: LenixLangToken? = null

    fun setInputSource(programCode: String?) {
        lexer.setInputSource(programCode)
    }

    fun parse(): LenixLangAst {
        return updateParserStatus(lexer.lex()) ?: throw ParseException("AST is null")
    }

    private fun updateParserStatus(tokens: List<LenixLangToken>): LenixLangAst? {
        if (tokens.isEmpty()) {
            return LenixLangProgramNode.emptyNode()
        }
        this.tokens.clear()
        this.tokens.addAll(tokens)
        currentPosition = 0
        currentToken = tokens[currentPosition]
        return program()
    }

    private fun match(tokenType: LenixLangTokenType, errorThrow: Boolean = false): Boolean {
        val currentToken = this.currentToken ?: throw InvalidTokenException("Unexpected token: null")
        if (currentToken.tokenType === tokenType) {
            currentPosition++
            if (currentPosition >= tokens.size) {
                this.currentToken = LenixLangToken(LenixLangTokenType.EOF, LenixLangTokenValue.EOF)
            } else {
                this.currentToken = tokens[currentPosition]
            }
            return true
        } else if (errorThrow) {
            throw InvalidTokenException(
                "Unexpected token `${currentToken.tokenValue}' typed `${currentToken.tokenType}' near line ${currentToken.lineNumber}, expected $tokenType",
            )
        }
        return false
    }

    private fun program(): LenixLangProgramNode {
        val token = currentToken
        var group = group()
        if (group != null) {
            val groups = mutableListOf(group)
            while (token?.tokenType !== LenixLangTokenType.EOF) {
                group = group()
                if (group == null) break
                groups.add(group)
            }
            return LenixLangProgramNode(groups)
        }
        return LenixLangProgramNode.emptyNode()
    }

    private fun group(): LenixLangGroupNode? {
        val token = currentToken ?: throw InvalidTokenException("Unexpected token: null")
        var attr = attribute()
        if (attr != null) {
            val attributes = mutableListOf(attr)
            while (token.tokenType !== LenixLangTokenType.EOF &&
                token.tokenType !== LenixLangTokenType.BRACKET_END &&
                token.tokenType !== LenixLangTokenType.ARRAY_END
            ) {
                attr = attribute()
                if (attr == null) break
                attributes.add(attr)
            }
            return LenixLangGroupNode(attributes.toTypedArray())
        }
        return null
    }

    private fun attribute(): LenixLangAttributeNode? {
        val token = currentToken ?: throw InvalidTokenException("Unexpected token: null")
        if (match(LenixLangTokenType.ID)) {
            match(LenixLangTokenType.COLON, errorThrow = true)
            val attrName = LenixLangStringNode(token)
            val block = block(attrName) ?: LenixLangBlockNode.emptyNode()
            return LenixLangAttributeNode(attrName, block)
        }
        return null
    }

    private fun array(arrayName: LenixLangStringNode): LenixLangArrayNode? {
        val token = currentToken ?: throw InvalidTokenException("Unexpected token: null")
        var block = blockNonArrayElement(arrayName)
        var index = 0
        if (block != null) {
            val elements = mutableListOf(LenixLangArrayNode.Companion.ArrayElement(index++, block))
            if (match(LenixLangTokenType.COMMA)) {
                while (token.tokenType !== LenixLangTokenType.EOF && token.tokenType !== LenixLangTokenType.ARRAY_END) {
                    block = blockNonArrayElement(arrayName)
                    if (block == null) break
                    elements.add(LenixLangArrayNode.Companion.ArrayElement(index++, block))
                    if (!match(LenixLangTokenType.COMMA)) break
                }
            }
            return LenixLangArrayNode(arrayName, elements.toTypedArray())
        }
        return null
    }

    private fun block(attrName: LenixLangStringNode): LenixLangBlockNode? {
        val block = blockNonArrayElement(attrName)
        if (block != null) return block
        val token = currentToken ?: throw InvalidTokenException("Unexpected token: null")
        return when (token.tokenType) {
            LenixLangTokenType.ARRAY_START -> {
                match(LenixLangTokenType.ARRAY_START, errorThrow = true)
                val array = array(attrName)
                match(LenixLangTokenType.ARRAY_END, errorThrow = true)
                if (array != null) LenixLangBlockNode(array) else LenixLangBlockNode.emptyNode()
            }
            else -> throw InvalidTokenException("Unexpected token `${token.tokenValue}' typed `${token.tokenType}' for block")
        }
    }

    private fun blockNonArrayElement(attrName: LenixLangStringNode?): LenixLangBlockNode? {
        val token = currentToken ?: throw InvalidTokenException("Unexpected token: null")
        return when (token.tokenType) {
            LenixLangTokenType.NUMBER -> {
                match(LenixLangTokenType.NUMBER, errorThrow = true)
                LenixLangBlockNode(LenixLangNumberNode(token))
            }
            LenixLangTokenType.ID -> {
                match(LenixLangTokenType.ID, errorThrow = true)
                LenixLangBlockNode(LenixLangStringNode(token))
            }
            LenixLangTokenType.STRING -> {
                match(LenixLangTokenType.STRING, errorThrow = true)
                LenixLangBlockNode(LenixLangStringNode(token))
            }
            LenixLangTokenType.BRACKET_START -> {
                match(LenixLangTokenType.BRACKET_START, errorThrow = true)
                val group = group()
                match(LenixLangTokenType.BRACKET_END, errorThrow = true)
                if (group != null) LenixLangBlockNode(group) else LenixLangBlockNode.emptyNode()
            }
            else -> null
        }
    }
}

open class InvalidTokenException(message: String) : ParseException(message)
open class ParseException(message: String) : RuntimeException(message)
