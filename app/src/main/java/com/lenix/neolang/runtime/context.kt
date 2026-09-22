package com.lenix.neolang.runtime

/**
 * NeoLang context — ported from Stryker for Lenix.
 * Provides hierarchical attribute storage for configuration.
 */
class LenixLangContext(val contextName: String) {
    companion object {
        private val emptyContext = LenixLangContext("<Context-Empty>")
    }

    private val attributes = mutableMapOf<String, LenixLangValue>()
    val children = mutableListOf<LenixLangContext>()
    var parent: LenixLangContext? = null

    fun defineAttribute(attributeName: String, attributeValue: LenixLangValue): LenixLangContext {
        attributes[attributeName] = attributeValue
        return this
    }

    fun getAttribute(attributeName: String): LenixLangValue {
        return attributes[attributeName] ?: parent?.getAttribute(attributeName) ?: LenixLangValue.UNDEFINED
    }

    fun getChild(contextName: String): LenixLangContext {
        var found: LenixLangContext? = null
        children.forEach {
            if (it.contextName == contextName) {
                found = it
            }
        }
        return found ?: emptyContext
    }

    fun getAttributes(): Map<String, LenixLangValue> = attributes

    fun getChildNames(): List<String> = children.map { it.contextName }
}
