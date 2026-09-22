package com.lenix.neolang.frontend

import com.lenix.neolang.runtime.LenixLangArray
import com.lenix.neolang.runtime.LenixLangContext
import com.lenix.neolang.runtime.LenixLangValue
import java.util.Stack

/**
 * NeoLang visitors — ported from Stryker for Lenix.
 */
class AstVisitor(private val ast: LenixLangAst, private val visitorCallback: IVisitorCallback) {
    fun start() {
        AstVisitorImpl.visitStartAst(ast, visitorCallback)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : IVisitorCallback> getCallback(): T = visitorCallback as T
}

internal object AstVisitorImpl {
    fun visitProgram(ast: LenixLangProgramNode, visitorCallback: IVisitorCallback) {
        visitorCallback.onStart()
        ast.groups.forEach { visitGroup(it, visitorCallback) }
        visitorCallback.onFinish()
    }

    fun visitGroup(ast: LenixLangGroupNode, visitorCallback: IVisitorCallback) {
        ast.attributes.forEach { visitAttribute(it, visitorCallback) }
    }

    fun visitAttribute(ast: LenixLangAttributeNode, visitorCallback: IVisitorCallback) {
        visitBlock(ast.blockNode, ast.stringNode.eval().asString(), visitorCallback)
    }

    fun visitArray(ast: LenixLangArrayNode, visitorCallback: IVisitorCallback) {
        val arrayName = ast.arrayNameNode.eval().asString()
        visitorCallback.onEnterContext(arrayName)
        ast.elements.forEach { visitArrayElementBlock(it.block, it.index, visitorCallback) }
        visitorCallback.onExitContext()
    }

    fun visitArrayElementBlock(ast: LenixLangBlockNode, index: Int, visitorCallback: IVisitorCallback) {
        val visitingNode = ast.ast
        when (visitingNode) {
            is LenixLangGroupNode -> {
                visitorCallback.onEnterContext(index.toString())
                visitGroup(visitingNode, visitorCallback)
                visitorCallback.onExitContext()
            }
            is LenixLangStringNode -> {
                definePrimaryData(index.toString(), visitingNode.eval(), visitorCallback)
            }
            is LenixLangNumberNode -> {
                definePrimaryData(index.toString(), visitingNode.eval(), visitorCallback)
            }
        }
    }

    fun visitBlock(ast: LenixLangBlockNode, blockName: String, visitorCallback: IVisitorCallback) {
        val visitingNode = ast.ast
        when (visitingNode) {
            is LenixLangGroupNode -> {
                visitorCallback.onEnterContext(blockName)
                visitGroup(visitingNode, visitorCallback)
                visitorCallback.onExitContext()
            }
            is LenixLangArrayNode -> {
                visitArray(visitingNode, visitorCallback)
            }
            is LenixLangStringNode -> {
                definePrimaryData(blockName, visitingNode.eval(), visitorCallback)
            }
            is LenixLangNumberNode -> {
                definePrimaryData(blockName, visitingNode.eval(), visitorCallback)
            }
        }
    }

    private fun definePrimaryData(name: String, value: LenixLangValue, visitorCallback: IVisitorCallback) {
        visitorCallback.getCurrentContext().defineAttribute(name, value)
    }

    fun visitStartAst(ast: LenixLangAst, visitorCallback: IVisitorCallback) {
        when (ast) {
            is LenixLangProgramNode -> visitProgram(ast, visitorCallback)
            is LenixLangGroupNode -> visitGroup(ast, visitorCallback)
            is LenixLangArrayNode -> visitArray(ast, visitorCallback)
        }
    }
}

interface IVisitorCallback {
    fun onStart()
    fun onFinish()
    fun onEnterContext(contextName: String)
    fun onExitContext()
    fun getCurrentContext(): LenixLangContext
}

open class IVisitorCallbackAdapter : IVisitorCallback {
    override fun onStart() {}
    override fun onFinish() {}
    override fun onEnterContext(contextName: String) {}
    override fun onExitContext() {}
    override fun getCurrentContext(): LenixLangContext {
        throw RuntimeException("getCurrentContext() not supported in this IVisitorCallback!")
    }
}

class VisitorFactory(private val ast: LenixLangAst) {
    fun getVisitor(callbackInterface: Class<out IVisitorCallback>): AstVisitor? {
        return try {
            AstVisitor(ast, callbackInterface.getDeclaredConstructor().newInstance())
        } catch (e: Exception) {
            null
        }
    }
}

class ConfigVisitor : IVisitorCallback {
    private var rootContext: LenixLangContext? = null
    private var currentContext: LenixLangContext? = null

    fun getRootContext(): LenixLangContext = rootContext!!

    fun getContext(contextPath: Array<String>): LenixLangContext {
        var context = getCurrentContext()
        contextPath.forEach { context = context.getChild(it) }
        return context
    }

    fun getAttribute(contextPath: Array<String>, attrName: String): LenixLangValue =
        getContext(contextPath).getAttribute(attrName)

    fun getArray(contextPath: Array<String>, arrayName: String): LenixLangArray =
        LenixLangArray.createFromContext(getContext(contextPath).getChild(arrayName))

    fun getStringValue(path: Array<String>, name: String): String? {
        val value = getAttribute(path, name)
        return if (value.isValid()) value.asString() else null
    }

    fun getBooleanValue(path: Array<String>, name: String): Boolean? {
        val value = getAttribute(path, name)
        return if (value.isValid()) value.asString().lowercase() == "true" else null
    }

    fun getNumberValue(path: Array<String>, name: String): Double? {
        val value = getAttribute(path, name)
        return if (value.isValid()) value.asNumber() else null
    }

    fun getProfileString(name: String, default: String): String =
        getAttribute(emptyArray(), name).let { if (it.isValid()) it.asString() else default }

    fun getProfileBoolean(name: String, default: Boolean): Boolean =
        getAttribute(emptyArray(), name).let { if (it.isValid()) it.asString().lowercase() == "true" else default }

    override fun onStart() {
        currentContext = LenixLangContext("global")
        rootContext = currentContext
    }

    override fun onFinish() {
        var context = currentContext
        while (context != null && context.parent != null) {
            context = context.parent
        }
        currentContext = context
    }

    override fun onEnterContext(contextName: String) {
        val newContext = LenixLangContext(contextName)
        newContext.parent = currentContext
        currentContext!!.children.add(newContext)
        currentContext = newContext
    }

    override fun onExitContext() {
        val context = currentContext
        if (context?.parent != null) {
            currentContext = context.parent
        }
    }

    override fun getCurrentContext(): LenixLangContext = currentContext!!
}

class DisplayProcessVisitor : IVisitorCallbackAdapter() {
    private val contextStack = Stack<LenixLangContext>()

    override fun onStart() {
        println(">>> Start")
        onEnterContext("global")
    }

    override fun onFinish() {
        while (contextStack.isNotEmpty()) {
            onExitContext()
        }
        println(">>> Finish")
    }

    override fun onEnterContext(contextName: String) {
        val context = LenixLangContext(contextName)
        contextStack.push(context)
        println(">>> Entering Context `$contextName'")
    }

    override fun onExitContext() {
        val context = contextStack.pop()
        println(">>> Exiting & Dumping Context ${context.contextName}")
        context.getAttributes().entries.forEach {
            println("     > [${it.key}]: ${it.value.asString()}")
        }
    }

    override fun getCurrentContext(): LenixLangContext = contextStack.peek()
}
