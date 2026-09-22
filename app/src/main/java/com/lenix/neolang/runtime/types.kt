package com.lenix.neolang.runtime

/**
 * NeoLang runtime types — ported from Stryker's NeoLang for Lenix.
 * Provides value types, arrays, and context for scripting/configuration.
 *
 * Useful for Lenix as a reusable scripting/configuration layer for:
 * - Color schemes
 * - Extra keys
 * - Profiles
 * - Desktop environment settings
 */

class LenixLangArray private constructor(
    val elements: List<LenixLangArrayElement>,
    override val size: Int = elements.size,
) : Collection<LenixLangArrayElement> {
    companion object {
        internal class PrimaryElement(val primaryValue: LenixLangValue) : LenixLangArrayElement() {
            override fun eval(): LenixLangValue = primaryValue
        }

        internal class BlockElement(val blockContext: LenixLangContext) : LenixLangArrayElement() {
            override fun eval(key: String): LenixLangValue = blockContext.getAttribute(key)
            override fun isBlock(): Boolean = true
        }

        fun createFromContext(context: LenixLangContext): LenixLangArray {
            val elements = mutableListOf<LenixLangArrayElement>()
            // Ensure we have enough capacity
            val maxIndex = maxOf(
                context.getAttributes().keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: -1,
                context.children.mapNotNull { it.contextName.toIntOrNull() }.maxOrNull() ?: -1,
            )
            val array = MutableList<LenixLangArrayElement?>(maxIndex + 1) { null }

            context.getAttributes().entries.forEach {
                val index = it.key.toIntOrNull() ?: return@forEach
                if (index in array.indices) array[index] = PrimaryElement(it.value)
                else {
                    // Expand if needed
                    while (array.size <= index) array.add(null)
                    array[index] = PrimaryElement(it.value)
                }
            }
            context.children.forEach {
                val index = it.contextName.toIntOrNull() ?: return@forEach
                if (index in array.indices) array[index] = BlockElement(it)
                else {
                    while (array.size <= index) array.add(null)
                    array[index] = BlockElement(it)
                }
            }
            // Filter nulls and keep order
            array.filterNotNull().forEach { elements.add(it) }
            return LenixLangArray(elements)
        }
    }

    operator fun get(index: Int): LenixLangArrayElement = elements[index]

    override fun contains(element: LenixLangArrayElement): Boolean = elements.contains(element)
    override fun containsAll(elements: Collection<LenixLangArrayElement>): Boolean =
        this.elements.containsAll(elements)

    override fun isEmpty(): Boolean = size == 0
    override fun iterator(): Iterator<LenixLangArrayElement> = elements.iterator()
}

open class LenixLangArrayElement {
    open fun eval(): LenixLangValue = LenixLangValue.UNDEFINED
    open fun eval(key: String): LenixLangValue = LenixLangValue.UNDEFINED
    open fun isBlock(): Boolean = false
}

class LenixLangValue(private val rawValue: Any) {
    fun asString(): String = rawValue.toString()

    fun asNumber(): Double {
        if (rawValue is Array<*>) return 0.0
        return try {
            rawValue.toString().toDouble()
        } catch (e: Throwable) {
            0.0
        }
    }

    fun asBoolean(): Boolean = asString().lowercase() == "true"

    fun isValid(): Boolean = this != UNDEFINED

    companion object {
        val UNDEFINED = LenixLangValue("<undefined>")
    }
}
