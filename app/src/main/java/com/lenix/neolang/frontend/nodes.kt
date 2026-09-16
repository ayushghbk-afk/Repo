package com.lenix.neolang.frontend

import com.lenix.neolang.runtime.LenixLangValue

/**
 * NeoLang AST nodes — ported from Stryker for Lenix.
 */
open class LenixLangAst {
    fun visit(): VisitorFactory = VisitorFactory(this)
}

open class LenixLangBaseNode : LenixLangAst()

class LenixLangArrayNode(
    val arrayNameNode: LenixLangStringNode,
    val elements: Array<ArrayElement>,
) : LenixLangBaseNode() {
    companion object {
        class ArrayElement(val index: Int, val block: LenixLangBlockNode)
    }
}

open class LenixLangAstBasedNode(val ast: LenixLangBaseNode) : LenixLangBaseNode() {
    override fun toString(): String = "${javaClass.simpleName} { ast: $ast }"
}

class LenixLangAttributeNode(
    val stringNode: LenixLangStringNode,
    val blockNode: LenixLangBlockNode,
) : LenixLangBaseNode() {
    override fun toString(): String = "LenixLangAttributeNode { stringNode: $stringNode, block: $blockNode }"
}

class LenixLangBlockNode(blockElement: LenixLangBaseNode) : LenixLangAstBasedNode(blockElement) {
    companion object {
        fun emptyNode(): LenixLangBlockNode = LenixLangBlockNode(LenixLangDummyNode())
    }
}

class LenixLangDummyNode : LenixLangBaseNode()

class LenixLangGroupNode(val attributes: Array<LenixLangAttributeNode>) : LenixLangBaseNode() {
    override fun toString(): String = "LenixLangGroupNode { attrs: ${attributes.contentToString()} }"

    companion object {
        fun emptyNode(): LenixLangGroupNode = LenixLangGroupNode(arrayOf())
    }
}

class LenixLangNumberNode(token: LenixLangToken) : LenixLangTokenBasedNode(token)

class LenixLangProgramNode(val groups: List<LenixLangGroupNode>) : LenixLangBaseNode() {
    override fun toString(): String = "LenixLangProgramNode { groups: $groups }"

    companion object {
        fun emptyNode(): LenixLangProgramNode = LenixLangProgramNode(listOf())
    }
}

class LenixLangStringNode(token: LenixLangToken) : LenixLangTokenBasedNode(token)

open class LenixLangTokenBasedNode(val token: LenixLangToken) : LenixLangBaseNode() {
    override fun toString(): String = "${javaClass.simpleName} { token: $token }"
    fun eval(): LenixLangValue = token.tokenValue.value
}
