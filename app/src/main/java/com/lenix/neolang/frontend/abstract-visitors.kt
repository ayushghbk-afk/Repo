package com.lenix.neolang.frontend

/**
 * Abstract visitors compatibility — ported from Stryker.
 * Actual implementation is in visitors.kt (ConfigVisitor, DisplayProcessVisitor).
 * This file exists for structural parity with Stryker's NeoLang module.
 */

abstract class LenixLangAbstractVisitor : IVisitorCallbackAdapter()
